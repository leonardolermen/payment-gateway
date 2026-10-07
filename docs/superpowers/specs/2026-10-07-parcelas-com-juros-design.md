# Parcelas com juros do lojista — design

Data: 2026-10-07. Assume o painel do merchant em `main`. Decisão do usuário (2026-10-07): o lojista
configura até quantas parcelas são **sem juros** e a **taxa mensal** acima disso; o pagador vê o valor
exato de cada opção antes de pagar (modelo "B" da análise do front). Substitui, para tentativas de ordem,
o "sempre ByMerchant sem juros" da spec do cartão (`2026-09-28-cartao-cielo-design.md` §1). Fora:
juros do emissor (`ByIssuer`), mais de 12 parcelas, tabela de taxa por número de parcelas, parcelas em
fatura de assinatura (continua 1x), `POST /v1/payments` avulso (o merchant já escolhe o valor ali).

## 1. O que existe
`InstallmentPlan.of(amount, n)` aceita 1–12 e exige `amount >= 500 × n`; a Cielo recebe
`Payment.Amount = amount`, `Installments = n`, `Interest = ByMerchant` fixo. A tentativa da ordem cobra
sempre `order.amount()` (`OrderAttemptService`, `CreateCardPayment`). O checkout público não diz nada
sobre parcelas: o front tem 1x–12x fixo no código e descobre o mínimo pelo `422`.

## 2. Configuração (`billing.installment_settings`, `V308`)

```
merchant_id, environment, max_installments 1..12, interest_free_up_to 1..max,
monthly_rate_bps 0..1000 (2,99% = 299), updated_at;  PK (merchant_id, environment)
```

Sem linha = o comportamento de hoje (`max 12`, `interest_free_up_to 12`, taxa 0): nada muda para quem
não configurar. Por ambiente, como tudo do merchant: a taxa de teste não vale em produção.

| rota (chave do merchant) | comportamento |
|---|---|
| `GET /v1/installment-settings` | a configuração do ambiente da chave, ou o default |
| `PUT /v1/installment-settings` | grava; `interest_free_up_to > max_installments` ou taxa fora de 0–1000 → `400` |

`PUT` é idempotente por estado; não entra no `IdempotencyFilter`. Evento `installment_settings.updated`
no outbox, para o merchant auditar quem mudou o preço.

## 3. Cálculo (`InstallmentPricing`, billing, puro)

Para `n <= interest_free_up_to`: parcela = `amount / n` (a Cielo divide; o total é `amount`).
Para `n > interest_free_up_to` com taxa `i` (ao mês): Tabela Price,
`parcela = amount × i / (1 − (1 + i)^−n)`, **arredondada para cima no centavo**; `total = parcela × n`.
Total múltiplo exato de `n`: a divisão da Cielo dá a mesma parcela que mostramos. Mínimo de R$ 5,00 por
parcela vale para a parcela calculada; opção abaixo dele não é oferecida. Contas em `BigDecimal`
com `MathContext.DECIMAL64`; teste de tabela contra valores calculados à mão (3x, 6x, 12x a 2,99%).

## 4. Checkout e API

`GET /v1/checkout/{token}` ganha `installment_options: [{count, installment_amount, total,
interest_free}]`, só quando `CARD` está em `methods`; o front deixa de ter regra própria.
`POST /v1/orders/{id}/payments` e `POST /v1/checkout/{token}/payments` com cartão: o gateway
**recalcula** o total pelo `installments` pedido (nunca confia num total vindo do cliente) e a tentativa
cobra esse total. Contagem não oferecida → `422 INVALID_INSTALLMENTS` como hoje.

A tentativa deixa de ter sempre o valor da ordem: `payments.amount` = total cobrado; o detalhe do cartão
ganha `interest_amount` (`total − order.amount`, 0 sem juros), em `PaymentResponse.card`, no resumo do
checkout e nos eventos `payment.*`. A ordem continua com o valor original; `OrderSettlement` já não
compara valores de cartão, e o reembolso total devolve o que foi cobrado (juros incluídos).

## 5. Testes
Unitários: `InstallmentPricing` (tabela, arredondamento, mínimo, taxa 0, `interest_free_up_to = max`).
Integração no app: sem configuração o checkout oferece 1–12 sem juros como hoje; com `max 10, sem juros
até 3, 2,99%` o checkout devolve 10 opções com 4x+ com juros; pagar 6x cobra o total da opção (WireMock
recebe `Payment.Amount` = total e `Installments = 6`); `installments = 11` → 422; chave LIVE não vê a
configuração TEST; `payment.completed` carrega `interest_amount`.

## 6. Decisões (para o `DECISOES.md`)
1. **Juros do lojista calculados pelo gateway (Price), não do emissor.** Rejeitado: `ByIssuer` — o
   pagador só veria o valor na fatura. Custo: o gateway passa a ser dono de uma conta financeira; um erro
   de arredondamento é cobrança errada, por isso o teste de tabela.
2. **A tentativa cobra o total com juros; a ordem guarda o valor sem juros.** Rejeitado: reescrever o
   valor da ordem. Custo: quem somava `payments.amount` como receita da ordem passa a ver o juro junto
   (está separado em `interest_amount`).
3. **Total múltiplo de `n`, parcela arredondada para cima.** Rejeitado: arredondar o total. Custo: até
   `n − 1` centavos a mais para o pagador, e nenhuma parcela diferente da anunciada.
