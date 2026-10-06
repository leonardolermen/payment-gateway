# Painel do merchant — o que falta na API

Data: 2026-10-06. Assume o checkout público em `main`. O front (`payment-gateway-web`, spec
`2026-10-06-painel-e-checkout-design.md` daquele repositório) já chama três coisas que o gateway não
tem; esta spec as acrescenta, sem mudar nenhum contrato existente. Decisão do usuário (2026-10-06): a
tela inicial do painel é a lista de cobranças, com o nome do cliente em cada linha. Fora: busca por
nome, filtro por data, chave com escopo de leitura e operadores (Plano H).

## 1. O que existe
`GET /v1/orders` exige `reference` e devolve as ordens com aquela referência; `GET /v1/customers` exige
`document` e devolve zero ou um cliente. A resposta da ordem traz `customer_id`, sem nome. O front pede
`GET /v1/orders?status=&cursor=&limit=` e `GET /v1/customers?cursor=&limit=` e recebe `400`
(parâmetro obrigatório ausente): a tela inicial e a aba Clientes não carregam.

## 2. `GET /v1/orders` sem `reference`

| parâmetro | regra |
|---|---|
| `limit` | 1–100, default 20 (como hoje) |
| `cursor` | id da última ordem da página anterior; a página seguinte é `id < cursor` |
| `status` | opcional, `OPEN`, `PAID`, `CANCELED` ou `EXPIRED`; outro valor → `400` |
| `reference` | como hoje; combinado com `cursor` ou `status` → `400` |

Ordem `id DESC`: ids são ULID, então é a ordem de criação, e o cursor é o mesmo padrão de
`GET /v1/payments` (o front já pagina assim). Só as ordens do **ambiente da chave**: uma chave TEST não
lista ordens LIVE. Índice novo `idx_orders_merchant_env_id (merchant_id, environment, id DESC)` em
`V307` (o `status` filtra sobre ele), e o par dele em `billing.customers`, parcial em
`deleted_at IS NULL`.

## 3. `customer_name` na ordem
`OrderResponse` ganha `customer_name` (nulo sem cliente ou com cliente apagado), em toda resposta de
ordem — lista, `GET`, criação, cancelamento, rotação. Campo novo, nada removido: cliente antigo da API o
ignora. Na lista, os nomes saem de **uma** consulta (`id IN (...)`, só a coluna `name`, sem abrir o
documento cifrado) e as tentativas de **uma** consulta (`order_id IN (...)`), em vez de duas por linha:
o painel repete a lista a cada 10 s.

Cliente apagado (`deleted_at`) não tem nome na ordem: apagar é o merchant dizendo que não quer mais o
dado à vista. Rejeitado: `customer: {id, name}` aninhado — muda a forma de um campo que já existe.

## 4. `GET /v1/customers` sem `document`
`limit` 1–100 (default 20) e `cursor` como em §2; só clientes ativos do ambiente da chave, `id DESC`.
`document` continua como hoje e não combina com `cursor` (`400`). Cada item é o `CustomerResponse` de
sempre, documento mascarado.

## 5. Testes
Integração no app: ordens de duas páginas com cursor, sem repetir nem pular; filtro `status=PAID` só
devolve pagas (cancelada não aparece); `status=XYZ` → 400; `reference` + `cursor` → 400; chave LIVE do
mesmo merchant não vê ordem TEST; `customer_name` presente na lista e no `GET`, nulo em ordem de
cliente apagado; clientes paginados, sem o apagado, documento mascarado; `document` + `cursor` → 400.
Repositório (billing, Testcontainers): a consulta de nomes ignora apagados e outro merchant.

## 6. Decisões (para o `DECISOES.md`)
1. **Listas do painel filtradas pelo ambiente da chave.** Rejeitado: listar todos os ambientes, como
   `GET /v1/payments` faz hoje. Custo: um merchant que queira ver TEST e LIVE juntos usa duas chaves.
2. **`customer_name` plano na ordem.** Rejeitado: objeto `customer` aninhado. Custo: se a ordem precisar
   de mais dados do cliente, vira mais um campo plano ou uma quebra de contrato.
