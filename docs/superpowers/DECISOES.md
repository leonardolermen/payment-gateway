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

## 2026-09-25 — Uma interface de provider sobre a espinha, extensão por método
`MethodProvider<ISSUE, ISSUED, STATUS>` com `issue`/`find`/`cancel` é o que Pix e boleto de fato têm em
comum; devolução, listagem e webhook são do Pix, o txid derivado é do boleto, e cada um vive na sua
extensão estreita. Rejeitado: uma interface gorda com `Set<PaymentMethod> capabilities()` e métodos que
lançam `UnsupportedOperationException` — o compilador deixa passar e você descobre em produção que o
provider não tem o produto; rejeitado também records neutros (`IssueRequest`/`IssueResult`) que Pix e
boleto preenchem pela metade, que é o mesmo DTO misturado que esta mudança tirou do request body, um andar
abaixo. Custo se errado: se um terceiro método não couber em `issue/find/cancel`, a espinha é o lugar
errado e a interface volta a se dividir — o preço é uma assinatura, não comportamento.

## 2026-09-25 — `PaymentMethod` no kernel
`MethodProvider.method()` precisa do enum e o kernel não pode importar `payments` (ArchUnit
`kernelImportsNothing`), então o enum subiu para `com.gateway.kernel.payment`: é vocabulário compartilhado
entre payments e providers, que é para o que o kernel existe. Valores persistidos inalterados, sem
migração. Rejeitado: um segundo enum no kernel traduzido de e para `PaymentMethod` — tabela de tradução
entre dois enums com os mesmos dois valores. Custo se errado: nenhum técnico; se um dia o produto do banco
deixar de ser 1-para-1 com o nosso método, aí sim aparecem dois enums e a tradução.

## 2026-09-25 — Request body polimórfico por `method`, quebrando o contrato
`POST /v1/payments` passou a desserializar em `PixPaymentRequest` ou `BolecodePaymentRequest` (sealed,
`@JsonTypeInfo` sobre `method`), cada um declarando só os seus campos. Rejeitado: manter o corpo flat com
todos os campos e validar por dentro — o corpo flat é exatamente o que obrigava a validação cruzada por
comparação de string (`"PIX".equals(method)` quatro vezes). Junto veio
`fail-on-unknown-properties: true`: sem isso o Jackson engoliria calado um `due_date` num corpo PIX, e
igualmente um `expires_in` escrito errado que então recebe a expiração padrão. Custo se errado: cliente que
mandava campo do outro método recebe 400 em vez de 422 com mensagem explicativa, e cliente que mandava
campo desconhecido (inclusive por typo) passa a receber 400 onde antes era ignorado; não há cliente em
produção hoje. Mensagem de `method` inválido preservada no `ErrorHandler`, porque mensagem de erro é
contrato.

## 2026-09-25 — Um flow por método, dono do create inteiro, tronco por composição
`PixPaymentFlow` e `BolecodePaymentFlow` implementam `PaymentFlow` e leem de cima para baixo;
`PaymentDraftFactory`, `PendingAdoption`, `CreateFailures` e `BolecodeFromQuery` são o tronco comum.
Rejeitado: template method com `PaymentService` dirigindo os passos — exigiria um objeto de contexto
passado entre eles e esconderia atrás de um flag a diferença que importa: no timeout o Pix pergunta e
decide na hora (o txid é nosso, `GET /cob` vazio é autoritativo), e o Bolecode fica CREATED para o sweeper
(o 202 do banco significa "em andamento"). Custo se errado: mais linhas que a versão template, e uma
mudança no tronco tem de ser lida nos dois flows. `PaymentFlows` exige exatamente um flow por valor do
enum na construção: método novo sem flow é falha de startup, não 500 no primeiro request de um merchant.

## 2026-09-25 — Invariante de formato é responsabilidade do tipo
`Document`, `PersonName`, `Uf` e `ZipCode` validam e normalizam no próprio factory, e `Payer`/`Address`
passaram a ser compostos deles em `kernel/party`; `PayerFactory` traduz `PayerData` (o pagador cru) em
`Payer`, e é dele o único conhecimento que os tipos não podem ter: que o campo se chama
`customer.address.zip` na API e que o código é `CUSTOMER_REQUIRED`. Os 14 `if` de
`PaymentService.validatePayer` viraram duas checagens de nulo e sete construções tipadas, com as dez
mensagens preservadas letra por letra (`PayerFactoryTest`). Rejeitado: Bean Validation com anotações —
poria a grafia dos campos da API dentro do domínio e brigaria com o mapeamento para `DomainException`.
Custo se errado: quatro tipos a mais no kernel.

## 2026-09-25 — `UnitOfWork` no lugar de afrouxar o ArchUnit
Os três colaboradores que escrevem linha durante um create precisavam de transação, e receber o
`TransactionTemplate` do Spring violaria `modelsHaveNoSpring` — cuja intenção é justamente manter Spring
fora do modelo. Rejeitado: estender o regex de nomes da regra para aceitar `Factory|Adoption|Failures`
(afrouxar a regra para o código passar), e rejeitado renomear os três para `*Service` só para casar com o
sufixo. Em vez disso eles recebem a porta `UnitOfWork`, com `TransactionalRunner` como único adaptador.
Custo se errado: uma indireção a mais, e uma inconsistência enquanto os serviços antigos continuam
recebendo o template direto.

## 2026-09-26 — `GET /v1/me` virou `GET /v1/merchant`
O recurso é o merchant por trás da API key, e nomear a rota pela perspectiva de quem chama só lê bem
enquanto existe um tipo de chamador; `/v1/merchant` diz o que a resposta é. A classe e o record
acompanham (`MeController`/`Me` → `MerchantController`/`Merchant`); o corpo não muda
(`{merchant_id, name, environment}`). Rejeitado: manter `/v1/me` por ser contrato, e rejeitado servir
as duas com a antiga redirecionando — não há cliente em produção, e uma rota viva "por enquanto" nunca
morre. Custo se errado: quem chamava `/v1/me` recebe 404 sem aviso; a autenticação não muda, porque
`ProtectedRoutes.requiresApiKey` casa pelo prefixo `/v1/` e não pelo caminho exato.

## 2026-09-28 — Fase 2: dividir por responsabilidade, e o que fica grande de propósito
A regra: arquivo passando de ~300 linhas ou classe com mais de ~7 dependências no construtor está fazendo
mais de uma coisa. `PaymentService` tinha 627 linhas e 11 dependências; saíram dele `PaymentQueries`
(leitura), `PaymentCancellation` (cancelar, o banco primeiro), `PixSettlement` (settle e o webhook
confirmado no banco) e `BoletoSettlement` (a consulta do boleto dizendo "pago"), e ele ficou com o create
e as pontas soltas do create (5 dependências). A divisão deixou `ExpirationService` com 8, e ele virou
`PaymentExpiration` (expirar PENDING perguntando ao banco antes, 7) e `StuckCreatedSweep` (adotar ou
falhar CREATED que o banco pode ter aceitado, 6). Seis classes, todas com `UnitOfWork` em vez do
template, então o ArchUnit não mudou. Corpos de método movidos sem alteração além do campo por onde
passam; testes só trocaram o bean injetado.
Ficam grandes de propósito: `Payment` (535 linhas) é o agregado com a tabela de transições, e dividir
espalharia a invariante que ele existe para guardar; `RefundService` (368) é um conceito só, o
reembolso de ponta a ponta; `PaymentsConfiguration` (383) é só wiring, um `@Bean` por classe, sem
lógica. Rejeitado: aplicar o limite de linhas como catraca mecânica (quebrar esses três até caberem) —
o número é sinal de responsabilidade demais, não a responsabilidade em si. Custo se errado: `Payment`
ou `RefundService` crescem sem que ninguém reabra a questão; a próxima classe acima do limite tem de
citar esta entrada ou ser dividida.

## 2026-09-28 — Fase 2: um handler por tipo de job, e a contagem corrigida
A divisão deixou `JobRunner` com 11 dependências porque ele guardava um colaborador por tipo de job e
decidia o tipo em dois métodos: um `switch` em `run` e dois `if` em `retry`, além de outros dois no laço
de claim (o give-up do refund DEAD e o reset do RECONCILE). Agora `JobHandler` é a strategy: um handler
por `JobType` (`ProcessWebhookJob`, `ExpirePaymentJob`, `PollRefundJob`, `ReconcileJob`, `PollBoletoJob`),
com `afterFailure` e `notYet` no lugar de um booleano `failed`, e um `finish` default que só o reset do
RECONCILE e o give-up do refund sobrescrevem. `JobHandlers` exige exatamente um handler por tipo na
construção, como `PaymentFlows`; o backoff genérico mora uma vez em `JobBackoff`. `JobRunner` ficou com
claim, lease e save (5 dependências). Rejeitado: manter o `switch` no `JobRunner` decidindo o tipo em dois
métodos — cada tipo novo exigiria lembrar de todos os lugares. Custo se errado: um `JobType` novo sem
handler agora derruba o startup em vez de falhar no primeiro job; e um tipo que precise reagir ao próprio
resultado depende do hook `finish`, que é fácil de esquecer.
Correção da entrada anterior: `PaymentsConfiguration` tem 412 linhas depois de todos os commits da fase
(não 383) — continua só wiring, um `@Bean` por classe, e continua grande de propósito.

## 2026-09-28 — Cartão pela Cielo: o cartão passa pelo gateway, e o PAN morre na chamada
O número e o CVV chegam em `POST /v1/payments` e existem em memória só dentro de `CardData`/`CardToken`,
entre a leitura do request e `SaleRequestFactory`. Rejeitado: Silent Order Post (o merchant precisaria de
credencial SOP e de um passo no front). Custo: o gateway entra no escopo PCI de dados em trânsito, pago com
`CardNumber` sem `toString` revelador, o mixin do Jackson no app, o `Masker` com PAN/CVV, o
`CieloPayloadMasker` nas mensagens de exceção e o teste `CardDataNeverLeavesTheRequestTest`. Custo se
errado: um log com PAN é incidente de segurança.

## 2026-09-28 — Negativa é resultado, não exceção
`Status 3/13` vira `FAILED` com `decline_code` e um 402 ao merchant; exceção é só o que impediu a Cielo de
responder. Rejeitado: tratar a negativa como `ProviderException` (entraria no caminho de retentativa).
Custo se errado: retentativa automática de uma negativa, o que as bandeiras penalizam.

## 2026-09-28 — Timeout sem transação é FAILED, e consulta que falha não é "sem transação"
Depois de um timeout (ou de um 201 em dúvida: Status 0, 12, 14) o gateway consulta por `MerchantOrderId`
(o id do pagamento): achou e decidido, adota; não achou ou ainda em dúvida, `FAILED` — ao contrário do
boleto, porque a Cielo não tem "em andamento" que dure e a Garantia de Cancelamento desfaz o `Status 0`.
Se a própria consulta falha, o pagamento fica `CREATED` e o sweeper pergunta de novo. Rejeitado: falhar
também quando a consulta falha (diria "não cobrei" sobre uma venda que pode segurar o limite do pagador).
Custo se errado: uma autorização que a consulta não achou e depois apareceu fica órfã até a reconciliação
(`CARD_ACTIVE_AT_PROVIDER`) apontar.

## 2026-09-28 — AUTHORIZED sem prazo próprio; a reconciliação avisa
Uma autorização não expira no gateway; depois de `card-capture-deadline` (5 dias) a reconciliação abre
`CAPTURE_OVERDUE` — depois de consultar a venda, para não sinalizar uma capturada por fora. Rejeitado:
expirar em N dias como o Pix (a Cielo não expira, e cancelar por conta própria libera um limite que o
merchant pode querer capturar). Custo se errado: limite preso no cartão do cliente até alguém olhar a
divergência.

## 2026-09-28 — card_id é nosso, o token é da Cielo, selado
O merchant recebe e usa `card_id` (ULID nosso); o `CardToken` da Cielo fica em `payments.cards`, selado
pela porta `kernel/security/Sealer` que `merchants` implementa com o `EnvelopeCipher`
(AAD `merchant|provider|environment|card`). Rejeitado: expor o `CardToken` ao merchant (prende o merchant
à Cielo e o token vira dado sensível na mão dele); e `payments` importar `merchants` para cifrar (a
fronteira proíbe). Custo se errado: perder a `GATEWAY_MASTER_KEY` perde todos os cartões guardados, como
já perde as credenciais.
Um cartão salvo entra em transação própria, antes da adoção do pagamento: uma venda aprovada sempre
confirma mesmo que salvar o cartão falhe (`card_id` nulo, WARN — não se perde uma cobrança aprovada por
causa de um `save_card`). Um adotador concorrente ou uma retentativa podem gravar o mesmo cartão duas
vezes (`payments.cards` sem unicidade por token ainda); aceito nesta fase, com dedupe por token como
follow-up.

## 2026-09-28 — CVV obrigatório com card_id, contra a spec
A spec dizia `cvv` opcional com `card_id`; o schema da cobrança com token na doc da Cielo lista
`"required": ["CardToken", "SecurityCode"]`. A doc ganha: 422 `CARD_INVALID` "cvv is required with
card_id". Rejeitado: seguir a spec e descobrir no sandbox. Custo se errado: um merchant que guardou o
cartão para cobrar sem pedir o CVV de novo não consegue; o smoke (passo 9 de `NOTES.md`) confirma e,
se a Cielo aceitar sem, uma entrada nova afrouxa a regra.

## 2026-09-28 — A notificação da Cielo: token da URL e um header fixo, só o hash guardado
A Cielo não assina nem oferece mTLS; a autenticação é o token da URL do merchant **e** o header
`X-Gateway-Notification-Key` que ele configura no site da Cielo. A chave mora num conceito próprio dos
merchants (`inbound_notification_keys`, V102) como SHA-256, comparada em tempo constante; sem os dois,
404. A rota fica no conector principal (a Cielo só entrega na 443) e o `MtlsPortFilter` passa a cercar só
`/v1/providers/itau/**`. Rejeitado: guardar a chave em claro ou cifrada (não há quem precise lê-la de
volta). Custo se errado: quem descobre o token de um merchant e a chave forja notificações — que não
movem nada sozinhas, porque o gateway sempre consulta a venda.
ChangeType 25 (cancelamento/reembolso parcial) abre `PARTIAL_REFUND_AT_PROVIDER`: a venda da Cielo
continua PAID depois de um reembolso parcial e `CardAuthorization` não guarda valor estornado, então a
notificação é a única evidência. Isso também dispara para os próprios reembolsos parciais do gateway
(sem como distinguir "já sabíamos" de "a Cielo fez sozinha" nesta fase) — follow-up, não corrigido aqui.
Um ChangeType ignorável sobre um pagamento ainda `CREATED` vira IGNORED sem tocar o pagamento.

## 2026-09-28 — Devolução de cartão síncrona, e o timeout reserva
A devolução do cartão é o void com `amount`, respondido na mesma chamada: `REQUESTED → COMPLETED | FAILED`
sem job de polling, contra o `paid_amount` (que a captura parcial diminui). Timeout ou 503 deixam o
`Refund` em `PROCESSING` com o valor reservado e uma divergência `REFUND_UNKNOWN`. A regra de reserva
(`RefundReservation`) é uma só, compartilhada com o Pix: o teto do Pix é o `amount` do pagamento, o do
cartão é o `refundable` (o que a captura ainda não devolveu) — mesma classe, tetos diferentes.
`CardRefunds` usa a porta `UnitOfWork`, como o resto do fluxo de cartão. Rejeitado: falhar o
reembolso no timeout (liberaria o valor enquanto o dinheiro pode já ter voltado; um segundo reembolso
devolveria duas vezes). Custo se errado: um reembolso que não aconteceu fica reservado até um humano
fechar a divergência.

## 2026-09-28 — Payment e RefundService crescem de novo, citando a entrada da Fase 2
`Payment` passa de 535 para 710 linhas (as transições do cartão precisam do estado privado do agregado);
`RefundService` ganha o despacho para `CardRefunds` e chega a 9 dependências; `WebhookInboxService` ganha
o despacho para `CardNotifications` e chega a 8. Segue a entrada "Fase 2: …" que manda a próxima classe
acima do limite citar a decisão ou ser dividida: esta cita. Rejeitado: dividir o agregado dentro da fase
do cartão (misturaria refactor e feature no mesmo commit). Custo se errado: o próximo método de pagamento
encontra um `Payment` ainda maior e a divisão fica mais cara.

## 2026-09-28 — Fora desta fase
Registrados como follow-up e não implementados: dedupe de cartões salvos por token (adotador concorrente
ou retentativa podem duplicar); suprimir `PARTIAL_REFUND_AT_PROVIDER` para os próprios reembolsos
parciais do gateway; valor estornado em `CardAuthorization`; `CieloDates` com parsing estrito, ao
contrário do parsing tolerante do Itaú; `RequestId` = id de correlação só quando ele é um GUID (ULID cai
para um UUID aleatório, porque a Cielo exige 36 caracteres no formato GUID e o `RequestId` é opcional).

## 2026-09-28 — O hash do corpo idempotente vira HMAC com chave do servidor
O corpo de `POST /v1/payments` com cartão carrega PAN e CVV, e `idempotency_keys.request_hash` guardava
um SHA-256 puro dele: com os outros campos adivinháveis, o PAN (espaço pequeno, com Luhn) sai por força
bruta do próprio banco (PCI DSS 3.5.1 pede hash com chave). Passa a ser HMAC-SHA256 sob
`gateway.idempotency.hmac-key` (`GATEWAY_IDEMPOTENCY_HMAC_KEY`); vazio, usa o `api-key-pepper` já
obrigatório, para nenhuma instalação quebrar. Não há hash guardado em produção, então nenhum replay em
voo muda de resultado; num ambiente com linhas antigas, uma repetição dentro das 24 h com a mesma chave
daria 422 em vez de replay. Rejeitado: não guardar hash (perde a detecção de chave reusada com corpo
diferente) e cifrar o corpo (ninguém precisa lê-lo de volta). Custo se errado: trocar a chave HMAC
transforma todo replay pendente em 422 até o TTL.

## 2026-09-28 — ChangeType 25 explicado pelo próprio reembolso vira `ignored`
Fecha o follow-up da entrada "A notificação da Cielo": se o pagamento tem reembolso `COMPLETED` ou
`PROCESSING` criado nas últimas 24 h, o ChangeType 25 grava um evento `ignored` em vez de abrir
`PARTIAL_REFUND_AT_PROVIDER` — a notificação não traz valor, então o reembolso recente é a melhor
explicação disponível. Rejeitado: comparar valores (não há valor na notificação nem em
`CardAuthorization`). Custo se errado: um reembolso feito direto na Cielo dentro dessa janela passa sem
divergência; a conciliação de totais ainda o vê.

## 2026-09-30 — Smoke no sandbox da Cielo: o cartão de timeout é dúvida, e o sandbox não tokeniza
Resultados em `docs/providers/cielo/NOTES.md`, "Smoke results". Dois fatos mudam o que se esperava:
o cartão terminado em 6 responde 201 com `Status 0` e `ReturnCode 99` — dúvida síncrona, resolvida
pela consulta por `MerchantOrderId` (que funcionou ao vivo) e registrada como `FAILED`/422
`PROVIDER_TIMEOUT`, não como decline 402; e o merchant de sandbox não tem tokenização (`SaveCard`
volta sem `CardToken`, `POST /1/card/` responde `CP900`), então `card_id` saiu `null` sem aviso.
Decisão: nada muda no código; o comportamento é o da spec §6.4 e "sem token, nada a guardar".
Follow-up: a resposta da API dizer por que o cartão não foi guardado (hoje o merchant só vê o `null`).
Custo se errado: um merchant em produção que pediu `save_card` e recebeu `null` cobra a segunda vez com
PAN de novo — funciona, mas ele não sabe se foi ele ou nós.

## 2026-10-02 — Fatura é ordem
A fatura de uma assinatura é uma `Order` com `subscription_id` e `invoice_number`, não uma entidade
própria: estado, tentativas e liquidação são os da ordem avulsa. Rejeitado: tabela `invoices` com estado
espelhando `orders` (duas máquinas de estado para manter iguais). Custo se errado: relatório por fatura
precisa filtrar `subscription_id IS NOT NULL`.

## 2026-10-02 — Uma tentativa ativa por ordem, garantida pelo banco
O índice parcial em `payments` (uma tentativa viva por `order_id`) é quem recusa a segunda; o 409
`ORDER_HAS_ACTIVE_PAYMENT` nasce da violação de unicidade. Rejeitado: lock na ordem dentro do serviço.
Custo se errado: zero em concorrência; um índice mal escrito deixaria duas cobranças vivas para a mesma
dívida.

## 2026-10-02 — Dunning não cancela
Fatura que falha deixa a assinatura `PAST_DUE` e é retentada nos dias de
`gateway.billing.dunning-retry-days`; esgotadas, sai `subscription.dunning_exhausted` e nada mais. O
próximo ciclo continua cobrando. Rejeitado: cancelar após N falhas (cortar serviço é decisão do
merchant). Custo se errado: assinatura `PAST_DUE` eterna se o merchant não agir.

## 2026-10-02 — Cartão recorrente sem CVV só para o job
Cobrança com cartão guardado sem CVV só para `EventSource.SYSTEM`, atrás de
`gateway.billing.card-recurring-enabled`; desligada, a fatura falha com `CARD_RECURRING_UNSUPPORTED` e vai
para o dunning. Rejeitado: guardar CVV (PCI proíbe). Custo se errado: uma afiliação Cielo que recuse
recorrência sem CVV transforma toda fatura de cartão em dunning até alguém desligar a bandeira.

## 2026-10-02 — O billing ouve o `OutboxRelay`, sem cursor próprio
`OrderSettlement` é listener interno do relay, idempotente por `processed_events`. Rejeitado: tabela
`outbox_consumers` com cursor por consumidor. Custo se errado: não dá para reprocessar só o billing sem
reentregar webhooks; se precisar, nasce o cursor.

## 2026-10-02 — Plano sem ambiente, assinatura com
O plano é catálogo do merchant; a assinatura herda o ambiente da chave que a criou. Rejeitado: plano por
ambiente. Custo se errado: plano de teste e de produção são o mesmo registro, e o merchant só os distingue
pela chave.

## 2026-10-02 — `payments` recebe `order_id` como string opaca
`Payment` guarda `order_id` sem saber o que é uma ordem; quem confere "valor da tentativa = valor da ordem"
é o `billing`. Rejeitado: `payments` consultar `billing` (ciclo entre módulos). Custo se errado: uma
tentativa criada fora do `billing` com `order_id` não passa por essa regra.

## 2026-10-02 — Idempotência do ciclo é estrutural, não uma Idempotency-Key sintética
Uma ordem por assinatura + número da fatura; uma nova execução retoma a fatura corrente e nunca retenta uma
tentativa `COMPLETED` ou ativa. Isto substitui a chave sintética da spec §6. Rejeitado: uma
Idempotency-Key gerada pelo job (os fluxos de pagamento internos não recebem chave). Custo se errado: um
caminho que crie tentativa sem passar por essa checagem cobra duas vezes.

## 2026-10-02 — O próximo ciclo é a mesma linha de job, reaberta
`BillSubscriptionJob.finish` devolve a própria linha a `PENDING` com a data do próximo ciclo: a tabela de
jobs tem uma linha por tipo + referência, então um `enqueue` da próxima execução seria no-op. Rejeitado:
um tipo de job por ciclo. Custo se errado: perder o `finish` (crash entre cobrança e reagendamento) deixa a
assinatura sem próximo ciclo até o lease vencer e a linha ser retomada.

## 2026-10-02 — `*_NOT_FOUND` vira `NOT_FOUND`, e o documento nunca volta num 404
Os códigos por recurso foram dobrados em `NOT_FOUND`, com a mensagem nomeando o recurso.
`DOCUMENT_IMMUTABLE` ficou inalcançável: o PATCH de customer não tem campo `document`, e
`fail-on-unknown-properties` responde 400 antes do serviço. `GET /v1/customers?document=` devolve lista de
0 ou 1, para que nenhum 404 ecoe o documento. Rejeitado: um código por recurso e 422 para documento
(contrato maior sem informação nova). Custo se errado: cliente que dependesse de `CUSTOMER_NOT_FOUND`
precisa ler a mensagem.

## 2026-10-02 — Serviços divididos para caber em ~7 dependências
`SubscriptionService`/`SubscriptionQueries`, `CycleOpener`/`SubscriptionBilling`/`InvoiceIssuer`,
`DunningLedger`/`Dunning` e `OrderAttemptService` (o que o plano chamava `OrderPayments`). Rejeitado: um
`SubscriptionService` com tudo (passaria de 7 dependências e 300 linhas). Custo se errado: mais saltos
para seguir um ciclo de cobrança.

## 2026-10-02 — Regras finas do dunning
O dunning só começa quando a fatura não tem nenhuma linha de tentativa; o último Pix/boleto reemitido que
expira emite `subscription.dunning_exhausted` em vez de recomeçar. A volta `PAST_DUE → ACTIVE` só acontece
quando nenhuma outra fatura aberta da assinatura tem retentativa pendente. `ExpireOrderJob` e
`DunningRetryJob` que encontram tentativa viva esperam em cadência fixa (`gateway.billing.order-expiry-recheck`,
PT1H) sem gastar retentativa. Rejeitado: reiniciar o dunning a cada expiração e recuperar na primeira fatura
paga. Custo se errado: assinatura marcada `ACTIVE` com outra fatura ainda devida, ou dunning infinito.

## 2026-10-02 — Pagamento em ordem fechada é divergência, e cartão adotado não derruba cobrança
Pagamento concluído numa ordem `CANCELED`/`EXPIRED` abre divergência `PAID_AFTER_CLOSE`, nunca exceção
(o dinheiro já entrou). A adoção do cartão salvo pelo customer da ordem roda depois da cobrança e nunca
falha a resposta: WARN e reparável. Rejeitado: rejeitar o evento, e adotar na mesma transação da cobrança.
Custo se errado: divergências que ninguém olha, e cartão salvo fora da lista do customer até reparo.

## 2026-10-02 — Fora desta fase (ordens e assinaturas)
Follow-ups, não decisões: cancelamento imediato de assinatura ainda não cancela a tentativa da fatura
aberta; duas execuções de `billOne` para a mesma assinatura além do lease do job poderiam cobrar o cartão
duas vezes (a correção é um marcador de tentativa por fatura); uma retentativa pendente marcada `SKIPPED`
numa fatura expirada/cancelada não reavalia a recuperação; a mensagem de `IN_PROGRESS` ainda aponta para
`/v1/payments?reference=`.
