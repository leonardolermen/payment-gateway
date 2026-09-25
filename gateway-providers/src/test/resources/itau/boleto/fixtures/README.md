# Itaú Bolecode fixtures — where each file came from

`../issue.openapi.json`, `../query.openapi.json` and `../instruction.openapi.json` are verbatim copies of
`docs/providers/itau/itau-ep9-api-recebimentos-v1-externo.openapi.json`,
`itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws.openapi.json` and
`itau-ep9-gtw-cash-management-ext-v2.openapi.json` (portal versions 1.0.7, 1.2.9, 2.75.147, read 2026-09-25).

## Copied verbatim from `components.examples` (pinned by `BoletoFixturesFromOpenApiTest`)

| file | OpenAPI | example |
|---|---|---|
| `post_boletos_pix_request_min.json` | issue | `requestPostBoletosPix.value` |
| `post_boletos_pix_200.json` | issue | `200boletoPixResponse.value` |
| `get_boletos_200.json` | query | `query_200_boletos_get_response.value` |
| `patch_baixa_200.json` | instruction | `200_instrucao.value.value` (the example nests `value` twice) |

## Derived — no example in the OpenAPI for this state

- `post_boletos_pix_202.json`, `post_boletos_pix_422.json`: the `bolecode202` / `bolecode422` schema `example` values, assembled into a body.
- `get_boletos_200_paid.json`, `get_boletos_200_canceled.json`, `get_boletos_200_rejected.json`, `get_boletos_200_awaiting.json`:
  `get_boletos_200.json` reduced to its second item (carteira 109), `numero_nosso_numero` set to `00000001`,
  `situacao_geral_boleto` set to `Pago` / `Baixado` / `Pagamento Rejeitado` / `Aguardando Crédito`. The paid one keeps the
  example's `pagamentos_cobranca` (valor 2100.00, data 2020-01-20); the others drop `pagamentos_cobranca` and `baixa`.
- `get_boletos_200_empty.json`: `{"data": [], "page": {...}}` — the shape the schema gives an unknown number.
- `get_boletos_404.json`: the `404` response example of the query OpenAPI.
- `patch_baixa_422_paid.json`: `{"codigo":"422","mensagem":"Boleto já liquidado","campos":[]}` — the 422 has no schema; the text is the
  portal's wording for a baixa on a settled boleto.

(Os arquivos de `query`/`instruction` listados nascem nas Tasks 4 e 5; o README já os documenta para não ser editado três vezes.)
