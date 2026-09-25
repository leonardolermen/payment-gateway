# Decisões

Append-only. Cada entrada: decisão, alternativa rejeitada, custo de estar errada.

## 2026-09-24 — Todo o código em inglês
Identificadores, comentários, mensagens, commits e README. Rejeitado: português como no Barrier e no
agent-orchestrator. Motivo: produto para terceiros e lib compartilhada com API pública em inglês. Custo se
errado: nenhum técnico; os docs de spec/plano continuam em português.

## 2026-09-24 — API key com SHA-256+pepper, não bcrypt
Chave de 130 bits aleatórios: força bruta é impossível com hash rápido; bcrypt custaria ~100 ms por
requisição. Rejeitado: bcrypt/argon2. Custo se errado: nenhum enquanto a chave for aleatória; se um dia
aceitarmos chave escolhida pelo merchant, a decisão cai.

## 2026-09-24 — Envelope AES-256-GCM com DEK por linha, mestra em env
Rejeitado: cifrar tudo direto com a mestra (rotacionar = recifrar tudo); KMS já no MVP (uma instância,
um operador). Custo se errado: a mestra em env é um segredo a mais para vazar; migrar para KMS é trocar
`MasterKey` e recifrar 32 bytes por linha.

## 2026-09-24 — Rate limit em memória
Uma instância. Rejeitado: Redis no MVP. Custo se errado: com duas instâncias o limite vira 2x até
trocar por bucket distribuído.

## 2026-09-24 — Admin key vazia fecha o admin
Rejeitado: "sem chave = dev aberto". Custo se errado: nenhum; dev exporta uma variável.

## 2026-09-24 — Sem provider fake; TEST é o sandbox real do Itaú
Rejeitado: um `FakePixProvider` em memória para os testes de contrato e para o ambiente TEST do
merchant. Motivo: um fake nunca diverge da API real do jeito que a API real diverge do OpenAPI dela —
foi olhando o portal que se descobriu que o sandbox usa `/api/oauth/jwt` sem mTLS, diferente de LIVE.
Custo se errado: bug de integração só aparece em produção, com dinheiro na mesa; os testes usam WireMock
com fixtures tiradas do OpenAPI, não um fake que inventa o próprio contrato.

## 2026-09-24 — txid = id do payment
Rejeitado: gerar um txid separado e guardar o mapeamento. Motivo: o Itaú aceita `[a-zA-Z0-9]{26,35}` e
o `PaymentId` (ULID) já cabe nesse formato; um id próprio é uma tabela de tradução a mais para manter
consistente. Custo se errado: se um dia o formato do id mudar e não couber mais no regex do Itaú, o
provider precisa de um mapeamento — hoje não precisa.

## 2026-09-24 — Timeout e UNAVAILABLE perguntam ao banco antes de falhar
Rejeitado: marcar o pagamento como falho direto na primeira exceção de rede. Motivo: um timeout ou 503
não diz se a cobrança chegou ao Itaú — falhar direto arrisca dizer "não cobrou" para um cliente que já
foi cobrado. Custo se errado: cobrança fantasma do ponto de vista do merchant (banco tem o dinheiro, gateway
diz que falhou) até a reconciliação de 15 em 15 minutos achar a divergência.

## 2026-09-24 — Devolução assíncrona por polling de 5 min × 288, FAILED + divergência no fim
Rejeitado: bloquear a resposta do `POST /refunds` esperando o status final (a API do Itaú é assíncrona,
`EM_PROCESSAMENTO` primeiro); polling indefinido sem teto. Motivo: 288 tentativas de 5 minutos = 24h, a
janela que o Itaú documenta para o webhook de status opcional também não chegar. Custo se errado: sem
teto, um refund travado em `PROCESSING` para sempre esconde o problema; com teto e sem virar
divergência, o merchant nunca sabe que o dinheiro não voltou.

## 2026-09-24 — Webhook de entrada por connector mTLS, com allow-list opcional de subject
Rejeitado: validar o certificado do Itaú dentro do controller Spring, atrás do load balancer TLS-terminado.
Motivo: o Itaú não assina o payload (sem HMAC), só apresenta certificado de cliente — a autenticação
É o handshake TLS, e terminar TLS antes do Spring perde o certificado do cliente. `WEBHOOK_MTLS_ALLOWED_SUBJECTS`
fica vazio (qualquer certificado assinado pela CA confiada) até descobrirmos se a CA do Itaú também assina
certificados de outros clientes do banco. Custo se errado: vazio para sempre, e se a CA for compartilhada,
qualquer outro cliente do Itaú consegue postar webhooks fabricados na nossa inbox.

## 2026-09-24 — Chave de idempotência por ambiente, 5xx deixa a linha IN_PROGRESS
Rejeitado: uma chave global por merchant (sem separar TEST/LIVE); e reprocessar automaticamente uma
chave cujo primeiro request deu 5xx. Motivo: um teste com a mesma chave de um request LIVE anterior não
pode reexecutar o request LIVE por engano — o ambiente entra no hash e no prefixo da chave. Um 5xx não
fecha a linha porque não se sabe se o request chegou a criar a cobrança no Itaú; reexecutar arriscaria
uma segunda cobrança (`createCharge` gera um txid novo a cada tentativa). Custo se errado: sem essa
separação, uma chave reaproveitada por acidente entre TEST e LIVE dispararia um pagamento real a partir
de um teste; sem o IN_PROGRESS após 5xx, um retry automático dobra a cobrança.

## 2026-09-24 — AAD do envelope inclui ambiente, não só merchant e provider
Já documentado em código (`ProviderCredentialService.aad`, comentário no método `find`): AAD
`merchant|provider|environment` — não é uma decisão nova do Plano B, é o motivo pelo qual uma linha TEST
trocada pela linha LIVE do mesmo merchant/provider falha a decifrar em vez de decifrar errado. Registrado
aqui porque o Plano B é o primeiro a ter duas linhas (TEST e LIVE) por merchant/provider de verdade.

## 2026-09-24 — CREATED → PENDING(SYSTEM) é o que o sweeper varre
Rejeitado: o sweeper de expiração olhar pagamentos em `CREATED` diretamente. Motivo: `CREATED` é o
instante entre validar o request e a chamada ao Itaú ainda não ter respondido — não tem `calendario.expiracao`
nem `pixCopiaECola` para expirar contra. O sweeper (`ExpirationService.sweepStuckCreated`, rodado dentro do
job RECONCILE a cada 15 min) promove um `CREATED` preso para `PENDING` com `source=SYSTEM` assim que
descobre o resultado da chamada ao Itaú (por `GET /cob/{txid}`, nunca recriando a cobrança), e só então
o pagamento entra no caminho normal de expiração. Custo se errado: sem essa promoção, um pagamento cuja
resposta HTTP se perdeu (rede caiu entre a cobrança ser criada no Itaú e a resposta chegar) fica
`CREATED` para sempre — nem paga, nem expira, nem aparece na reconciliação.

## 2026-09-24 — Pago × FAILED/CANCELED é divergência, nunca evento ao merchant
Rejeitado: emitir um evento `PAYMENT_PAID_LATE` ou similar quando a reconciliação encontra um `pix[]`
pago para um `txid` que o gateway já marcou `FAILED` ou `CANCELED`. Motivo: essa situação nunca deveria
acontecer (é exatamente o que a reconciliação existe para achar) e o merchant não tem uma ação de código
para tomar a partir de um evento — precisa de alguém olhando o valor e decidindo o que fazer com o
dinheiro. Custo se errado: virar evento automático dá ao merchant a falsa sensação de que o caso está
tratado; como divergência em `reconciliation_divergences`, fica visível para operação até alguém resolver.

## 2026-09-24 — Eventos de webhook em maiúsculas, iguais aos da API
Rejeitado: um vocabulário de eventos de webhook separado do enum interno de status (ex.: `payment.paid`
em vez de `PAID`). Motivo: o merchant já lê `GET /v1/payments/{id}` e vê o status em maiúsculas; um
segundo vocabulário só para o webhook é uma tradução a mais para manter sincronizada e testar. Custo se
errado: os dois vocabulários divergem silenciosamente na primeira mudança que só atualiza um dos dois,
e o merchant vê um status na API e outro no webhook para o mesmo pagamento.

## 2026-09-24 — Webhook é gatilho, o banco é a verdade
Rejeitado: completar o pagamento com o que o corpo do webhook diz (e2eid, valor), confiando no mTLS e no
token da URL. Motivo: o corpo é uma afirmação de quem conseguiu chegar ao endpoint; o merchant despacha a
mercadoria no nosso `payment.completed`. Agora `WebhookInboxService` usa o webhook só como gatilho:
`PaymentService.settleFromWebhook` pergunta `GET /cob/{txid}` e só completa se o banco disser `CONCLUIDA`
com um `pix[]` de mesmo `endToEndId`; o valor que vale é o do banco, e `settle` recusa (divergência
`AMOUNT_MISMATCH`) um Pix de valor diferente do da cobrança. Não confirmado: evento `ignored` e divergência
`UNCONFIRMED_WEBHOOK`, nada ao merchant; banco fora do ar: exceção e o job tenta de novo. O mesmo vale para
devoluções no webhook (escopo do merchant, e2eid do próprio pagamento, `GET /devolucao` decide), e a
reconciliação passou a abrir divergência para `COMPLETED` aqui × cobrança não `CONCLUIDA` (ou e2eid
diferente) no banco. Custo: um GET por webhook, o mesmo que o job de expiração já paga. Custo se errado
(confiar no corpo): um POST forjado, ou vazado de outro merchant, vira mercadoria entregue sem dinheiro.

## 2026-09-24 — UNKNOWN mantém a reserva da devolução
Rejeitado: marcar `FAILED` a devolução que esgota o orçamento de polling (24 h em `EM_PROCESSAMENTO`), e
marcar `FAILED` na hora uma devolução cujo PUT recebeu 503/504. Motivo: `FAILED` libera o valor na conta
de reserva, e nos dois casos o dinheiro ainda pode sair no banco; o merchant pediria outra devolução por
cima e o pagador receberia duas vezes. Agora: 503 é tratado como timeout (`PROCESSING`, o polling decide;
`findRefund` vazio depois de `refund-not-found-grace`, 30 min, vira `FAILED`), e o orçamento esgotado vira
`RefundState.UNKNOWN` — terminal para o polling, mas contado como reservado — com `refund.unknown` ao
merchant e divergência `REFUND_UNKNOWN` na mesma transação. Uma palavra posterior do banco ainda move
UNKNOWN para COMPLETED ou FAILED. Custo: o merchant fica sem poder devolver aquele valor até alguém olhar
a divergência. Custo se errado (liberar a reserva): devolução em dobro, dinheiro que não volta.

## 2026-09-25 — O sandbox do Itaú prova contrato, não comportamento
Registrado, não decidido: a primeira chamada real ao sandbox (token em `/api/oauth/jwt`, `PUT /cob`,
`GET /cob`, `PATCH /cob`) passou de ponta a ponta com credenciais do portal. Mas o sandbox é um mock
estático — devolveu o `txid` e o recebedor do exemplo da documentação em vez de ecoar o nosso, e aceita
qualquer `chave`. Consequência: "funciona no sandbox" cobre autenticação e formato de requisição; pagar
o QR, webhook de entrada, devolução e reconciliação só se provam em produção. Achado: o gateway guarda o
txid que o banco devolve (`PaymentService.adoptPending`) e não o que enviou; em produção são iguais, mas
um txid diferente do enviado deveria ser resposta inválida, porque a recuperação por timeout consulta o
banco pelo id do pagamento. Fica como pendência pequena. Diagramas de `docs/architecture.md` reduzidos a
cinco figuras de alto nível: as anteriores listavam serviços e tabelas e ninguém conseguia ler.

## 2026-09-25 — Bolecode: polling da consulta de detalhe em vez de webhook de boleto
Rejeitado: o webhook de boleto (Boletos v3). Motivo: exige que o gateway exponha um servidor OAuth2 client
credentials para o banco pegar token, e o payload da notificação não consta no OpenAPI — só entra com
homologação. O `POLL_BOLETO` consulta `GET /boletos` a cada 6 h até a data limite + 2 dias; a reconciliação
faz a mesma pergunta para todo `PENDING` com mais de `reconciliation-min-age`. Custo: até 6 h de atraso para
saber de um pagamento em código de barras, que já compensa em D+1. Custo se errado: nenhum dinheiro perdido,
só latência; o webhook pode entrar depois sem mudar o modelo (o polling vira rede de segurança).

## 2026-09-25 — Bolecode expira pela data limite, não pelo vencimento
Rejeitado: expirar no `due_date`. Motivo: boleto vencido paga (com juros, fora de escopo); expirar no vencimento
cancelaria cobranças que ainda entram. `expires_at` é 23:59:59 America/Sao_Paulo da `payment_limit_date`; a
expiração pergunta ao banco antes e não manda baixa (após a data limite o banco recusa sozinho). Custo se
errado: um pagamento no último dia, creditado no dia útil seguinte — coberto pelos 2 dias de folga do poll e
por `EXPIRED → COMPLETED` via `PROVIDER_POLL`/`RECONCILIATION`.

## 2026-09-25 — Nosso número sequencial por merchant, alocado com o CREATED
Rejeitado: derivar do ULID (não cabe em 8 dígitos); um contador em memória. Motivo: `boleto_numbers` com
`INSERT … ON CONFLICT DO UPDATE … RETURNING` na mesma transação do `CREATED` — dois threads nunca repetem, um
rollback devolve o número. Reutilização (45 dias após baixa/liquidação) não é tratada: 10^8 por merchant.
Custo se errado: um número repetido é 4xx do banco na emissão, não dinheiro.

Pendência aberta, fora deste plano: o contador é por merchant, mas o banco exige nosso número único por CONTA
Itaú (`beneficiary_id`). Dois merchants apontando para a mesma conta Itaú podem alocar o mesmo nosso número e
colidir na emissão — não é bug de concorrência do gateway, é o escopo do contador estar um nível abaixo do que
o banco exige. Custo se errado: rejeição 4xx na emissão para o segundo merchant a usar aquele número; nenhum
dinheiro em risco. Decisão de produto pendente: contador por conta compartilhado entre merchants, ou uma
conta Itaú por merchant.

## 2026-09-25 — Sem devolução por boleto
Rejeitado: fingir a devolução com uma transferência. Motivo: a API não tem devolução de boleto; uma
transferência seria dinheiro saindo por um caminho que o gateway não controla nem concilia. `paid_via = BOLETO`
→ `422 REFUND_NOT_SUPPORTED`; pago pelo QR devolve como Pix. Custo se errado: o merchant resolve fora do gateway,
como já faz hoje para qualquer boleto.

## 2026-09-25 — O id da baixa e o txid do Pix vêm da fórmula do OpenAPI, não do UUID
Registrado: a spec dizia `cancel(idBoletoIndividual)` e `txid = BL + agência + 00 + conta + carteira + 0000000 +
nosso número`. O OpenAPI da cash_management define `id_boleto` como agência+conta+DAC+carteira+nosso número
(23 chars, `minLength 23`), e o da emissão define o txid como `BL` + agência(4) + conta(7) + carteira(3) +
nosso número(15). O código segue o JSON para reconstruir o id da baixa; o txid armazenado no pagamento é o
nosso (`payment.id()`), não o derivado — a fórmula derivada só entra para recuperar uma resposta de emissão
perdida, confirmada com `GET /cob/{txid}` antes de valer (divergência `PIX_TXID_UNCONFIRMED` se não bater; um
eco do banco que discorda vira WARN em `adoptPending`, não falha dura). Custo se errado: um cancelamento 404 no
banco (visível em `provider_requests`) e uma divergência — nunca um pagamento casado errado, porque o webhook
Pix casa pelo txid que o banco devolveu na emissão.

## 2026-09-25 — Índice de unicidade do txid é por merchant, não global
Registrado: `uq_payments_provider_txid` (V203) é `(merchant_id, provider, details->'pix'->>'txid')`, não só
`(provider, txid)`. Motivo: o banco deriva txids de Bolecode da conta, e o sandbox compartilhado devolve txids
enlatados dos próprios exemplos — um índice global colidiria entre merchants de teste na mesma conta sandbox.
Custo se errado (índice global): inserção de pagamento falhando por colisão entre merchants que nunca
deveriam competir pelo mesmo txid.


## 2026-09-25 — Timeout na emissão sem boleto na consulta deixa CREATED, não FAILED
Rejeitado: marcar `FAILED` na hora, como o Pix faz. Motivo: a emissão responde 202 "operação em andamento";
uma consulta vazia um segundo depois não prova nada. O `sweepStuckCreated` pergunta de novo após
`stuck-created-after` e decide (adota ou falha). Custo: o merchant recebe 422 `PROVIDER_TIMEOUT` e precisa
consultar por `reference` antes de tentar com outra chave — o mesmo protocolo do 409 `IN_PROGRESS`. Custo se
errado (falhar na hora): um boleto emitido e pagável que o gateway chamou de falho.

## 2026-09-25 — Correção: qual txid cada trilho guarda (a entrada de hoje sobre a fórmula estava invertida)
Registrado: a entrada "O id da baixa e o txid do Pix vêm da fórmula do OpenAPI, não do UUID", escrita mais
cedo hoje, descreveu a regra ao contrário. A regra correta, conferida em `PaymentService.java`: no trilho
**Pix puro**, `adoptPending` guarda o txid **nosso** (`p.id()`) e só emite WARN quando o banco ecoa outro
(desde o commit `548df4b`) — nunca adota o do banco. No trilho **Bolecode**, `adoptPendingBolecode` guarda o
txid **do banco** (`issued.pixTxid()`, vindo da resposta da emissão), porque esse txid é derivado da conta
pelo banco e o gateway não pode escolher o dele; a fórmula derivada (`BoletoProvider.pixTxidFor`) só entra
para *recuperar* esse txid quando a resposta da emissão se perdeu (202/timeout), confirmada com
`GET /cob/{txid}` antes de valer — confirmação vazia ou divergente abre `PIX_TXID_UNCONFIRMED` em vez de
adotar sem checar. `docs/providers/itau/NOTES.md` foi corrigido para refletir isto nas duas seções (Pix e
Bolecode). Custo se errado (a versão invertida, publicada por engano): alguém lendo a NOTES concluiria que
o Bolecode guarda `payment.id()` como o Pix, e um mismatch de txid no Bolecode seria tratado como aviso
inofensivo em vez do sinal real de que o `nosso_numero`/conta não bateram — o tipo de erro que
`PIX_TXID_UNCONFIRMED` existe para pegar.
