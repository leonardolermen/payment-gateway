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
