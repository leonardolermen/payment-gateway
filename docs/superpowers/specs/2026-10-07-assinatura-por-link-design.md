# Cobrança recorrente: assinatura por link — design

Data: 2026-10-07. Assume Planos E (assinaturas) e o checkout público em `main`. Decisão do usuário
(2026-10-07): ao criar a cobrança, o lojista diz se ela é **avulsa** ou **recorrente**; a recorrente
gera uma ordem por ciclo e cobra sozinha, e quando o pagador fica inadimplente a recobrança entra. Fora:
cupom, proração, troca de plano no meio do ciclo, Pix Automático, parcelas em fatura (continua 1x).

## 1. O que existe e o que falta
`POST /v1/subscriptions {customer_id, plan_id, method, card_id?}` já cria a assinatura, e o job do ciclo
abre uma fatura (ordem com `subscription_id`, `invoice_number`, `period`) e cobra: cartão salvo sem CVV,
Pix novo ou boleto. A recobrança (dias 1, 3, 7) põe a assinatura em `PAST_DUE` e nunca cancela sozinha.

Falta o começo: com `method = CARD` a criação exige `card_id`, e um cartão só é salvo pagando uma ordem
com `save_card`. Quem não tem o cartão do pagador — o caso comum — não consegue criar a assinatura. E a
fatura nasce sem link utilizável (o token é descartado; só a rotação emite um).

## 2. Assinatura que espera o primeiro pagamento
`POST /v1/subscriptions` aceita `method = CARD` **sem** `card_id`. A assinatura nasce em
`INCOMPLETE` (status novo) e a primeira fatura é aberta **na criação**, com `checkout_url` devolvido na
resposta (`first_invoice: {order_id, checkout_url}`), como em `POST /v1/orders`.

- O checkout de uma fatura de assinatura `INCOMPLETE` oferece só `CARD` e força `save_card`: o pagador
  vê "o cartão fica salvo para as próximas cobranças de {plano}" antes de pagar.
- Fatura paga → `InvoiceSettlementHook` liga o cartão salvo à assinatura (`card_id`), passa para
  `ACTIVE`, agenda o próximo ciclo. Evento `subscription.activated`.
- Fatura que expira ou é cancelada → assinatura `INCOMPLETE_EXPIRED` (terminal), sem recobrança: nunca
  houve consentimento para cobrar o cartão.
- Pix e boleto não mudam: a assinatura nasce `ACTIVE` e cada ciclo gera a cobrança; a novidade é que a
  fatura de cada ciclo nasce com link (§3).

`SubscriptionTransitions` ganha `INCOMPLETE → ACTIVE | INCOMPLETE_EXPIRED` e `INCOMPLETE → CANCELED`
(o merchant desiste antes do pagamento). `V309` amplia o `CHECK` de `billing.subscriptions.status` e
torna `card_id` opcional enquanto `INCOMPLETE` (`CHECK (method <> 'CARD' OR card_id IS NOT NULL OR
status IN ('INCOMPLETE', 'INCOMPLETE_EXPIRED', 'CANCELED'))`).

## 3. Link em toda fatura
`CycleOpener` passa a guardar o token emitido e devolvê-lo **uma vez** no evento `invoice.created`
(`checkout_url`), em vez de descartá-lo: é por onde o merchant manda o link ao pagador. `GET` continua
sem reexibir (DECISOES 2026-10-06); a rotação continua valendo. Fatura de recobrança (`invoice.updated`)
reemite o link do mesmo jeito. Inadimplente = fatura aberta de assinatura `PAST_DUE`; o painel a mostra
com "Gerar novo link" e "Cobrar de novo" (`POST /v1/orders/{id}/payments` com o cartão salvo).

## 4. Ordem avulsa vs. ciclo
Não há campo novo na ordem: `subscription_id` já separa a avulsa (nulo) da fatura de um ciclo. A escolha
"avulsa ou recorrente" é de **qual rota** se chama: `POST /v1/orders` ou `POST /v1/subscriptions`. A
ordem avulsa continua sem ciclo; uma ordem de ciclo só nasce do job ou da criação da assinatura.

## 5. Testes
Integração no app: criar assinatura CARD sem `card_id` → `INCOMPLETE` com `first_invoice.checkout_url`;
pagar pelo checkout com cartão → cartão salvo, assinatura `ACTIVE`, segundo ciclo cobra o cartão salvo
sem CVV (WireMock vê `CardToken`); fatura expirada → `INCOMPLETE_EXPIRED` e nenhum job de recobrança;
checkout de fatura `INCOMPLETE` oferece só `CARD` e ignora `save_card: false`; `invoice.created` traz
`checkout_url` e o banco só o hash; assinatura Pix nasce `ACTIVE` como hoje.

## 6. Decisões (para o `DECISOES.md`)
1. **Primeira fatura paga pelo link salva o cartão e ativa a assinatura.** Rejeitado: o merchant coletar
   o cartão por fora e mandar `card_id` (é o que trava hoje). Custo: um status a mais e a regra de que
   checkout de fatura `INCOMPLETE` só aceita cartão.
2. **Fatura expirada sem pagamento encerra a assinatura `INCOMPLETE`, sem recobrança.** Rejeitado:
   tratar como `PAST_DUE`. Custo: o merchant cria outra assinatura se o pagador voltar depois.
3. **O link da fatura sai uma vez, no evento.** Rejeitado: guardar o token para reexibir (mesma razão da
   ordem). Custo: o merchant que perder o evento rotaciona.
