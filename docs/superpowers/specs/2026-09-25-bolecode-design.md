# Bolecode (Boleto com Pix) — design

Data: 2026-09-25. Complementa `2026-09-23-payment-gateway-design.md` (Fase 2: boleto).
Decisões do usuário nesta sessão: um único método `BOLECODE` (híbrido, sem boleto puro);
liquidação por QR e por código de barras desde já; código de barras detectado por **polling**
da consulta de detalhe, não por webhook de boleto.

## 1. O que é, nas palavras do banco

Bolecode = um boleto registrado + um QR Code Pix na mesma emissão. O pagador escolhe: paga o
Pix (liquida na hora, chega pelo webhook Pix) ou paga o boleto (compensa em D+1, chega só por
consulta). O Itaú expõe isso em quatro APIs, todas com sandbox no portal (produto, versão lida
em 2026-09-25):

| papel | produto | versão | operação usada |
|---|---|---|---|
| emissão | `itau-ep9-api-recebimentos-v1-externo` (Boleto com Pix) | 1.0.7 | `POST /boletos-pix` |
| situação | `itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws` (Consulta de detalhe) | 1.2.9 | `GET /boletos?id_beneficiario&codigo_carteira&nosso_numero` |
| baixa | `itau-ep9-gtw-cash-management-ext-v2` (Emissão e Instrução) | 2.75.147 | `PATCH /boletos/{id_boleto}/baixa` |
| webhook | `itau-ep9-gtw-boletos-boletos-v3-ext-aws` (Boletos v3) | 1.34.1 | **fora de escopo** (ver §8) |

Os OpenAPIs estão em `docs/providers/itau/*.openapi.json` e são a fonte dos fixtures e da
validação de schema, como no Pix.

Base URLs (produção / sandbox):

- emissão: `https://pix-pj.api.itau.com/recebimentos-pix/v1` / `https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1`
- situação: `https://secure.api.cloud.itau.com.br/boletoscash/v2` / `https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1`
- baixa: `https://api.gateway.itau.com.br/cash_management/v2` / `https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2`

Autenticação: mesma da Pix (STS client credentials sobre mTLS em produção, `x-itau-apikey`,
`x-itau-correlationID`; sandbox = `POST /api/oauth/jwt` sem mTLS). **Atenção:** o OpenAPI da
cash_management declara `tokenUrl: https://sts.itau.com.br/api/oauth/token`, diferente do
`/as/token.oauth2` da Pix. O `ItauTokenClient` passa a receber o token URL por chamada e cacheia
por (credencial, token URL). Sem evidência de qual vale em produção para cada API, o
`application.yml` tem um token URL por API, ambos configuráveis.

Fatos do schema que moldam o desenho:

- Request de emissão exige `beneficiario.id_beneficiario` (agência 4 + conta 7 + DAC 1),
  `dado_boleto.codigo_carteira` (109), `codigo_especie`, `tipo_boleto = "a vista"`,
  `descricao_instrumento_cobranca = "boleto_pix"`, `pagador` com nome, CPF **ou** CNPJ e endereço
  completo (logradouro, bairro, cidade, UF, CEP 8 dígitos), `dados_individuais_boleto[0]` com
  `numero_nosso_numero` (8 dígitos, único por 45 dias após baixa/liquidação), `data_vencimento`,
  `valor_titulo` (`^\d+\.\d{2}$`) e opcional `data_limite_pagamento`.
- Caracteres proibidos em todo o payload: `[ : < > & ; ' " \` ( ) # * / | ü` e as palavras
  `http`, `javascript`, `alert`. O gateway sanitiza nome, endereço e descrição antes de enviar
  e recusa com 422 o que não der para sanitizar.
- Resposta 200: `dados_individuais_boleto[0]` com `id_boleto_individual` (UUID), `dac_titulo`,
  `codigo_barras` (44), `numero_linha_digitavel` (47), `data_limite_pagamento`; `dados_qrcode`
  com `txid` (`BL` + agência + `00` + conta + carteira + `0000000` + nosso número = 31 chars),
  `emv` (copia e cola), `base64` (imagem, descartada), `chave`.
- `etapa_processo_boleto = "simulacao"` valida sem emitir. Usado só no smoke do sandbox, nunca
  no fluxo do produto.
- Consulta de detalhe devolve `dados_individuais_boleto[].situacao_geral_boleto` ∈
  `Em Aberto | Pago | Liquidado | Pagamento Rejeitado | Aguardando Crédito | Creditado | Baixado`
  e bloco `pagamento` com `valor_pago_total_cobranca`, `data_inclusao_pagamento`,
  `codigo_meio_pagamento_boleto_cobranca`.
- A API de emissão devolve 202 "operação em andamento" (raro): tratado como timeout — consulta
  por nosso número antes de decidir.

## 2. Modelo (`gateway-payments`)

`Payment.method` ganha `BOLECODE`. `details` (JSONB, já polimórfico) passa a ter duas chaves:

```json
{
  "pix":    {"txid": "BL2938…", "pixCopiaECola": "…", "location": null, "endToEndId": null},
  "boleto": {"nossoNumero": "00001234", "idBoletoIndividual": "uuid", "linhaDigitavel": "…47",
             "codigoBarras": "…44", "dueDate": "2026-10-01", "paymentLimitDate": "2026-10-31",
             "paidVia": null}
}
```

`BoletoDetails` é um record em `payment/boleto/`, `BoletoDetailsJson` ao lado (mesmo padrão de
`PixDetails`). Índice `uq_payments_provider_txid` continua valendo (txid `BL…` é único por
banco); novo índice em `(merchant_id, details->'boleto'->>'nossoNumero')`.

Estados: os mesmos do Pix. Diferenças na tabela `PaymentTransitions`:

| transição | fontes | nota |
|---|---|---|
| `PENDING → COMPLETED` | `PROVIDER_WEBHOOK`, `RECONCILIATION`, **`PROVIDER_POLL`** (nova fonte) | poll = consulta de detalhe |
| `PENDING → EXPIRED` | `SYSTEM` | só após `paymentLimitDate` + folga, **não** após `dueDate` |
| `EXPIRED → COMPLETED` | provider | boleto liquidado no último dia, ou crédito atrasado |
| `PENDING → CANCELED` | `API` | via baixa; recusada se o banco já mostra pago |

`markCompleted` ganha `paidVia ∈ {PIX, BOLETO}` gravado em `details.boleto.paidVia` e no evento.
Evento novo: `payment.completed` já existe; o payload passa a incluir `paid_via`.

Devolução: `RefundService` recusa `BOLECODE` pago por boleto com `REFUND_NOT_SUPPORTED` (422).
Pago pelo QR (há `endToEndId`), a devolução Pix funciona como hoje.

Nosso número: tabela `payments.boleto_numbers(merchant_id, next_value)` com
`UPDATE … RETURNING` na transação de `CREATED`. 8 dígitos, começa em 1. Reutilização (45 dias)
não é tratada: 10^8 números por merchant bastam.

## 3. Contrato (`gateway-kernel`, `provider/boleto/`)

```java
public interface BoletoProvider {
  String id();
  IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r);
  Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero);
  void cancel(ProviderCredentials c, String idBoletoIndividual);
}
record BoletoIssueRequest(String nossoNumero, Money amount, LocalDate dueDate, LocalDate paymentLimitDate,
    Payer payer, String description) {}
record Payer(String name, String document, Address address) {}
record Address(String street, String district, String city, String state, String zip) {}
record IssuedBoleto(String idBoletoIndividual, String linhaDigitavel, String codigoBarras,
    LocalDate paymentLimitDate, String pixTxid, String pixCopiaECola, String pixKey) {}
record BoletoStatus(BoletoSituation situation, Money paidAmount, Instant paidAt, String paidChannel,
    String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate) {}
// the four identity fields exist so that a charge adopted after a timeout (§7) can be completed
// from the query alone
enum BoletoSituation { OPEN, PAID, SETTLED, AWAITING_CREDIT, CREDITED, PAYMENT_REJECTED, CANCELED }
```

`ProviderException` e `ProviderCredentials` são os mesmos do Pix. `ProviderGateway.resolve`
devolve `Resolved(pix, boleto, credentials)`; `boleto` pode ser vazio para um provider sem
boleto (nenhum hoje).

## 4. Credencial e configuração do merchant (`gateway-merchants`)

A credencial `ITAU` (mesmo payload cifrado) ganha campos opcionais:

| campo | uso |
|---|---|
| `beneficiary_id` | `id_beneficiario` (12 dígitos) — obrigatório para emitir |
| `wallet_code` | `codigo_carteira`, padrão `109` |
| `species_code` | `codigo_especie`, padrão `01` (DM) |

`ItauCredentials.parse` aceita e valida (`^\d{12}$`, `^\d{3}$`, `^\d{2}$`). Emissão sem
`beneficiary_id` falha antes de qualquer chamada com `PROVIDER_CREDENTIALS_MISSING` e
`detail = "beneficiary_id"`. O `README` e o `.env.example` ganham as variáveis
`ITAU_SANDBOX_BENEFICIARY_ID` etc.

## 5. API do merchant (`gateway-app`)

`POST /v1/payments` com `"method": "BOLECODE"`:

```json
{
  "amount": 12990, "currency": "BRL", "method": "BOLECODE", "reference": "order-42",
  "description": "Pedido 42",
  "customer": {"name": "Joao da Silva", "document": "12345678901",
               "address": {"street": "Rua das Flores 10", "district": "Centro", "city": "Sao Paulo",
                           "state": "SP", "zip": "01310100"}},
  "due_date": "2026-10-01",
  "payment_limit_days": 30
}
```

Regras: `customer` completo é obrigatório (422 `CUSTOMER_REQUIRED` com o campo faltante);
`document` é CPF (11) ou CNPJ (14) só dígitos; `due_date` padrão hoje + 3 dias, mínimo hoje;
`payment_limit_days` padrão 30, máximo 3650. Resposta 201:

```json
{
  "id": "…", "status": "PENDING", "method": "BOLECODE",
  "boleto": {"linha_digitavel": "…", "codigo_barras": "…", "due_date": "2026-10-01",
             "payment_limit_date": "2026-10-31", "paid_via": null},
  "pix": {"txid": "BL…", "copia_e_cola": "…", "location": null, "end_to_end_id": null},
  "expires_at": "2026-10-31T23:59:59Z"
}
```

`POST /v1/payments/{id}/cancel` funciona para `BOLECODE` (baixa). `POST …/refunds` → 422
`REFUND_NOT_SUPPORTED` quando `paid_via = BOLETO`. Filtros e eventos existentes não mudam.

## 6. Providers Itaú (`gateway-providers`, `itau/boleto/`)

- `BoletoPixApiClient`: `POST /boletos-pix`. Request montado de `BoletoIssueRequest` +
  credencial (beneficiário, carteira, espécie). Sanitização dos textos (§1). 200 → `IssuedBoleto`;
  202 → `ProviderException(TIMEOUT)`; 400/422 → `DECLINED` com `campos[]` no log mascarado;
  401/403 → `UNAUTHENTICATED`; 503/504 → `UNAVAILABLE`/`TIMEOUT`.
- `BoletoQueryClient`: `GET /boletos` na consulta de detalhe. Mapeia `situacao_geral_boleto`
  → `BoletoSituation` (aceita com e sem acento). Lista vazia ou 404 → `Optional.empty()`.
- `BoletoInstructionClient`: `PATCH /boletos/{id}/baixa`. 200/204 → ok; 422 com mensagem de
  "já pago/liquidado" → `ProviderException(CONFLICT)`.
- `ItauBoletoProvider` compõe os três. Endpoints em `ItauEndpoints` ganham `boletoIssue`,
  `boletoQuery`, `boletoInstruction` e `cashManagementTokenUrl`.
- Cada DTO validado contra o OpenAPI correspondente por `RequestSchemaValidationTest`; fixtures
  copiados dos `examples` dos OpenAPIs e pinados por `FixturesFromOpenApiTest`.

## 7. Fluxos (`gateway-payments`)

**Criar.** `PaymentService.createBolecode(cmd)`: valida pagador → reserva nosso número e grava
`CREATED` (uma transação) → `issue` fora de transação → `adoptPending` grava `PENDING`,
`details.pix` + `details.boleto`, outbox `payment.pending`, job `POLL_BOLETO` (+6 h) e job
`EXPIRE_PAYMENT` (`paymentLimitDate` 23:59:59 America/Sao_Paulo + `expirationGrace`).
Timeout/UNAVAILABLE/202: `find(nossoNumero)` — encontrado, adota (e o `IssuedBoleto` vem da
consulta: a consulta traz linha digitável e código de barras; o EMV do Pix vem de
`GET /cob/{txid}` com o txid reconstruído `BL` + agência + `00` + conta + carteira + `0000000` +
nosso número); não encontrado após `stuckCreatedAfter`, `FAILED`.

**Pago pelo QR.** Idêntico ao Pix: webhook → `settleFromWebhook` → `GET /cob/{txid}` → `COMPLETED`
com `paidVia = PIX`. O `WebhookInboxService` já casa por txid; nada muda além de aceitar
`method = BOLECODE`.

**Pago por código de barras.** `BoletoPollingService.poll(paymentId)` (job `POLL_BOLETO`):
`find(nossoNumero)` →

| situação | efeito |
|---|---|
| `OPEN`, `AWAITING_CREDIT` | reagenda +6 h (até `paymentLimitDate` + 2 dias, depois para) |
| `PAID`, `SETTLED`, `CREDITED` | `COMPLETED` (`PROVIDER_POLL`, `paidVia = BOLETO`, valor e data do banco); valor ≠ título → divergência `AMOUNT_MISMATCH` sem completar |
| `PAYMENT_REJECTED` | divergência `BOLETO_REJECTED`, continua `PENDING`, reagenda |
| `CANCELED` (baixado) e gateway `PENDING` | divergência `CANCELED_AT_BANK`; não muda o estado (alguém cancelou fora do gateway) |
| vazio | divergência `NOT_FOUND_AT_BANK` depois de 2 consultas vazias |

Se o pagamento já estiver `COMPLETED` via Pix quando o poll rodar, o job termina sem efeito
(evento `ignored`). Se o poll disser pago por boleto **e** houver Pix já confirmado, divergência
`DOUBLE_PAYMENT` — o pagador pagou duas vezes; humano decide.

**Cancelar.** `cancel`: `find` primeiro; pago → completa e devolve 409 `ALREADY_PAID`; aberto →
`cancel(idBoletoIndividual)` → `CANCELED`. O QR do Bolecode morre junto com o boleto (baixa
remove a cobrança Pix, segundo a doc do produto); a expiração cobre se não morrer.

**Expirar.** `ExpirationService` para `BOLECODE`: `find` primeiro; aberto/baixado → `EXPIRED`;
pago → `COMPLETED`. Roda na data limite, não no vencimento.

**Reconciliar.** `ReconciliationService` ganha uma passada por `PENDING` de `BOLECODE` com mais
de `reconciliationMinAge`: mesma decisão do poll. A janela do Pix (`GET /cob`) segue igual e
também enxerga os txids `BL…`.

## 8. Fora de escopo (com o porquê)

- Webhook de boleto (Boletos v3): exige que o gateway exponha um servidor OAuth2 client
  credentials para o banco pegar token, e o payload da notificação não consta no OpenAPI. Entra
  quando houver homologação; o polling continua como rede de segurança.
- Juros, multa, desconto, abatimento, protesto, negativação, alteração de vencimento, envio por
  e-mail pelo banco, sacador avalista, boleto sem Pix, `cobv`/agendamento, Pix Automático.
- Devolução de boleto (não existe na API).
- Reutilização de nosso número.

## 9. Testes

- `gateway-kernel`: sem lógica nova além de records.
- `gateway-providers`: `BoletoPixApiClientContractTest`, `BoletoQueryClientContractTest`,
  `BoletoInstructionClientContractTest` (WireMock; fixtures dos OpenAPIs), sanitização,
  mapeamento de situação com/sem acento, `RequestSchemaValidationTest` estendido aos três
  schemas, `ItauCredentialsTest` para os campos novos, `ItauTokenClient` com dois token URLs.
- `gateway-payments`: `PaymentTransitionsTest` com `PROVIDER_POLL`; `BoletoDetailsJsonTest`;
  `RecordingBoletoProvider` em `support/`; `BolecodeServiceIntegrationTest` (criar, timeout +
  adoção, poll cada situação, cancelar aberto/pago, expirar, reconciliar, refund recusado,
  double payment); nosso número concorrente (dois threads, sem repetição).
- `gateway-app`: `BolecodeFlowIntegrationTest` (201 com boleto e pix, 422 sem customer,
  cancel, refund 422), contrato JSON da resposta.
- Ao final: smoke no sandbox real (emissão em `simulacao` e `efetivacao`, consulta, baixa),
  documentado em `NOTES.md` como o do Pix.

## 10. Decisões (para o DECISOES.md)

- **Polling em vez de webhook de boleto.** Custo: até 6 h de atraso para saber de um pagamento
  em código de barras, que já compensa em D+1. Custo se errado: nenhum dinheiro perdido, só
  latência; o webhook pode entrar depois sem mudar o modelo.
- **Expiração pela data limite, não pelo vencimento.** Boleto vencido paga; expirar no vencimento
  cancelaria cobranças que ainda entram.
- **Nosso número sequencial por merchant.** Alternativa rejeitada: derivar do ULID (não cabe em
  8 dígitos). Custo: uma linha por merchant com `UPDATE … RETURNING`.
- **Sem devolução por boleto.** A API não devolve; fingir com transferência seria dinheiro
  saindo por um caminho que o gateway não controla.
