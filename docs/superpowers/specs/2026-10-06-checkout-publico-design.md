# Checkout público — design

Data: 2026-10-06. Assume Planos A–G em `main`. Decisões do usuário (2026-10-06): o pagador final abre
a tela de pagar por um link, sem login (opção 1); o link autoriza por **token de checkout guardado como
hash na ordem** (opção 1); o front mora em repositório separado, `payment-gateway-web`, e fala com a API
por CORS. Fora: tela do merchant (é o front), planos e assinaturas no checkout, operadores (Plano H).

## 1. O que existe
`POST /v1/orders` cria a ordem; `POST /v1/orders/{id}/payments` abre uma tentativa com corpo por
método (`PixAttemptBody`, `BolecodeAttemptBody`, `CardAttemptBody`), com uma tentativa ativa por ordem
(`uq_payments_order_active` + `AttemptSlot`); `GET /v1/orders/{id}/payments` lista. Tudo exige a chave
do merchant (`ApiKeyAuthFilter`, `ProtectedRoutes.isProtected` = `/v1/**` menos admin e providers).
`RateLimitFilter` limita por chave, em memória. Nenhuma rota serve um terceiro sem chave.

## 2. Modelo (`V306__orders_checkout_token.sql`)

```
ALTER TABLE billing.orders ADD COLUMN checkout_token_hash CHAR(64);
CREATE UNIQUE INDEX ux_orders_checkout_token ON billing.orders (checkout_token_hash);
```

- Token `chk_` + 32 bytes aleatórios (`SecureRandom`) em base64url sem padding, gerado em
  `OrderFactory` para toda ordem nova (inclusive faturas de assinatura: o link de uma fatura é o mesmo
  mecanismo). Hash SHA-256 com o mesmo pepper das chaves de API (`merchants` já tem `KeyHasher`; o
  billing recebe um port `TokenHasher` em `billing/order/checkout/`, implementado no app pelo mesmo
  pepper — o billing não importa `merchants`).
- Ordens existentes ficam com hash nulo e sem link; o `GET` responde `checkout_url: null`. Não há
  backfill: um token gerado depois do fato nunca foi mostrado a ninguém.
- `checkout_url` = `gateway.checkout.base-url` (ex.: `https://pay.exemplo.com/pay/`) + token; devolvido
  **em todo** `POST /v1/orders` e `GET /v1/orders/{id}` do merchant. O token em claro só existe na
  resposta: a ordem guarda o hash. Como o merchant pode pedir o link de novo? Não pode — `POST
  /v1/orders/{id}/checkout-token/rotate` gera outro, invalida o anterior (409 `ORDER_CLOSED` se a
  ordem não está `OPEN`). Rejeitado: guardar o token em claro para reexibir. Custo: o merchant que perdeu
  o link rotaciona e manda o novo.

## 3. API pública (`gateway-app/api/checkout/`, sem chave)

| rota | comportamento |
|---|---|
| `GET /v1/checkout/{token}` | `{order_id, merchant_name, amount, currency, description, status, expires_at, methods, active_payment}`; `methods` = os que o merchant tem provider configurado no ambiente da ordem; `active_payment` = resumo da tentativa viva (`id, method, status, pix{qr_code, copy_paste, expires_at}, boleto{digitable_line, pdf_url, due_date}`) ou nulo |
| `POST /v1/checkout/{token}/payments` | mesmo corpo de `POST /v1/orders/{id}/payments`; 201 com o resumo da tentativa; `409 ORDER_HAS_ACTIVE_PAYMENT` como hoje; `Idempotency-Key` **opcional** (o pagador não é cliente da API; a tentativa ativa única já impede a dupla cobrança) |
| `GET /v1/checkout/{token}/payments/{id}` | resumo da tentativa, para o polling; 404 se não é da ordem |
| `POST /v1/checkout/{token}/payments/{id}/cancel` | cancela a tentativa viva (ex.: o pagador trocou de Pix para cartão); só Pix e boleto; cartão autorizado não |

Regras: token desconhecido → `404 NOT_FOUND` (nunca 401, nada a autenticar); ordem `CANCELED`,
`EXPIRED` ou `PAID` → `GET` responde normalmente com o `status` (a tela mostra "pago" ou "indisponível")
e os `POST` respondem `410 ORDER_CLOSED`. O resumo nunca inclui o `payer` nem o documento do cliente:
é a tela de um terceiro. `merchant_name` vem de `merchants`.

`CheckoutService` em `billing/order/checkout/` resolve o token (hash → `OrderRepository.findByCheckoutTokenHash`)
e delega para `OrderAttemptService.attempt(order, request, EventSource.CHECKOUT)` — `EventSource`
ganha o valor `CHECKOUT`, e os eventos `payment.*` da tentativa saem como hoje, com `source` para o
merchant saber que foi o pagador.

Filtros: `ProtectedRoutes.isProtected` exclui `/v1/checkout/`; `IdempotencyFilter` não cobre essas
rotas (sem merchant, não há escopo de chave); **`CheckoutRateLimitFilter`** por IP
(`X-Forwarded-For` do primeiro proxy confiável, senão `remoteAddr`), 60/min por IP, Bucket4j em memória
como o do merchant. `CardDataNeverLeavesTheRequestTest` ganha as rotas novas no seu varrimento.

## 4. CORS
`GATEWAY_CORS_ORIGINS` (lista, vazia por default = CORS desligado). Quando preenchida, `CorsFilter`
antes dos de autenticação, para `/v1/**`: origens exatas, métodos `GET,POST,PATCH,DELETE`, headers
`Content-Type, X-Api-Key, Idempotency-Key`, expõe `X-Next-Cursor`, `max-age` 1 h. Sem credenciais de
cookie: a chave vai no header. Rejeitado: `*` com chave no header (o navegador aceita, mas qualquer
site poderia chamar a API com a chave de uma vítima que a colou numa extensão).

## 5. Testes
Integração no app: `POST /v1/orders` devolve `checkout_url` e o banco guarda só o hash (consulta
direta); `GET /v1/checkout/{token}` sem chave responde 200 e sem `payer`; token errado 404; ordem
cancelada → `GET` 200 com `status` e `POST` 410; `POST .../payments` Pix cria a tentativa e o
`GET .../payments/{id}` mostra o copia-e-cola; segunda tentativa 409; rotação invalida o token antigo;
CORS: preflight de origem listada responde os headers, origem não listada não; rate limit por IP 429
na 61ª. Unitários: geração e hash do token; `CheckoutRateLimitFilter` lê o IP certo.

## 6. Decisões (para o `DECISOES.md`)
1. **Token na ordem, hash no banco, mostrado uma vez.** Rejeitado: URL assinada por HMAC (não revoga
   sem trocar o segredo; expõe o id). Custo: coluna e rotação explícita.
2. **Rotas públicas sem `Idempotency-Key`.** Rejeitado: exigir do pagador. Custo: nenhum — a tentativa
   ativa única já é a idempotência que importa.
3. **Rate limit por IP, em memória.** Rejeitado: por token (um atacante troca de token mais fácil do
   que de IP). Custo: atrás de NAT, um prédio inteiro divide 60/min; o limite é propriedade.
4. **CORS por lista explícita, vazio por default.** Custo: cada ambiente do front entra na lista.
