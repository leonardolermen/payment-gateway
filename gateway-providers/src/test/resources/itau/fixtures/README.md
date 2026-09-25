# Itaú fixtures — where each file came from

The OpenAPI next to this directory (`../openapi.json`) is a verbatim copy of
`docs/providers/itau/itau-ep9-api-regulatorio-pix-v2-externo.openapi.json`.

## Copied verbatim from `components.examples` (pinned by `FixturesFromOpenApiTest`)

| file | OpenAPI example |
|---|---|
| `put_cob_request_min.json` | `request_put_cobranca_imediata_campos_obrigatorios.value` |
| `put_cob_201.json` | `response_200_cobranca_imediata_txid.value` |
| `get_cob_200_active.json` | `200_cobranca_txid.value.value` (the example nests `value` twice) |
| `patch_cob_cancel_request.json` | `request_patch_cobranca_imediata_status.value` |
| `put_devolucao_request.json` | `request_put_devolucao_campos_obrigatorios.value` |
| `get_devolucao_200_done.json` | `200_devolucao.value` |
| `get_cob_list_200.json` | `200_cobrancas.value.value` |

## Derived — no example in the OpenAPI for this state

- `put_devolucao_201_processing.json`: `201_devolucao.value` with `status` set to
  `EM_PROCESSAMENTO`, `horario.liquidacao` and `motivo` removed. The OpenAPI's
  201 example shows `DEVOLVIDO`, but Itaú's "informações adicionais" page says the
  refund is asynchronous (NOTES.md, "refund (devolução)").
- `get_cob_200_completed.json`: `get_cob_200_active.json` with `status` `CONCLUIDA`
  and one `pix[]` item (`endToEndId` from the `200_cobrancas` example, `valor`
  equal to the charge, same `txid`) — the shape of a paid charge per NOTES.md.
- `error_400_cob_operacao_invalida.json`, `error_404_cob_nao_encontrado.json`:
  RFC 7807 bodies with the Bacen `type` URIs NOTES.md lists (`CobOperacaoInvalida`,
  `CobNaoEncontrado`); the OpenAPI has the schema but no example body.
- `webhook_pix.json`: the inbound webhook payload described on Itaú's
  "informações adicionais" page (NOTES.md, "Inbound webhook"); the `pix[]` item is
  the one from `200_cobrancas`, with `infoPagador` as a string, `componentesValor`,
  and one refund in `devolucoes` already `DEVOLVIDO`.
- `webhook_pix_null_fields.json`: same payload with `devolucoes: null`,
  `infoPagador: null` and no `componentesValor` — the optional fields as the bank
  may send them.
