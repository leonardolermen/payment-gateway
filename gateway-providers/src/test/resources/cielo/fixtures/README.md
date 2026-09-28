# Cielo fixtures

Source: the Cielo E-commerce API reference, the `.md` pages under
https://docs.cielo.com.br/ecommerce-cielo/ (read 2026-09-28). The Cielo publishes no OpenAPI file;
every example is copied from the OpenAPI block embedded in each page, with the `\n`/`\"` escaping
undone.

| file | origin |
|---|---|
| post_sales_request_simplified.json | verbatim — reference/criar-pagamento-credito, request "Cartão de crédito simplificado" |
| post_sales_request_token.json | verbatim — reference/cartao-tokenizado-api, "Request Example" |
| post_sales_201_captured.json | verbatim — reference/criar-pagamento-credito, 201 "Cartão de crédito simplificado" |
| post_sales_201_card_on_file.json | verbatim — reference/criar-pagamento-credito, 201 "Cartão de crédito com Card On File" |
| post_sales_201_token_authorized.json | verbatim — reference/cartao-tokenizado-api, 201 "Result" |
| post_sales_201_authorized_saved.json | derived from post_sales_201_captured: Capture false, Status 1, ReturnCode "4", no captured fields, SaveCard true, CardToken from reference/criar-cardtoken |
| post_sales_201_denied.json | derived: Status 3, ReturnCode "51" (page/abecs), no AuthorizationCode |
| post_sales_201_not_finished.json | derived: Status 0, ReturnCode "001" (reference/api-codes, "Crédito - não finalizado") |
| error_400_list.json | verbatim — reference/api-errors-code-message, "Exemplo" |
| error_400_expiration.json | derived: row 126 of the same page's table, in the example's shape |
| put_capture_200.json | verbatim — reference/capturar-apos-autorizacao, 200 "Result" |
| put_void_200.json | verbatim — reference/cancelamento-paymentid, 200 "Result" |
| put_void_200_refunded.json | derived from put_void_200: Status 11 (reference/payment-status, "Refunded") |
| get_sale_200_credit.json | verbatim — reference/consulta-paymentid-api, 200 "Transação de crédito" |
| get_sale_200_authorized.json | derived from get_sale_200_credit: Capture false, Status 1, no captured fields |
| get_sales_by_order_200.json | verbatim — reference/consulta-merchantorderid-api, 200 "Result" (note `ReceveidDate`, the Cielo's spelling) |
| post_card_request.json | verbatim — reference/criar-cardtoken, "Request Example" |
| post_card_201.json | verbatim — reference/criar-cardtoken, 201 "Result" |
| notification_change_type_2.json | verbatim — docs/webhook, the notification example |
| notification_status_changed.json | derived: ChangeType 1, PaymentId of get_sale_200_credit, no RecurrentPaymentId (only for ChangeType 2/4) |

Not used, on purpose: the 201 "Elo via Link de Pagamento" is not valid JSON (a comma is missing
after `"SolutionType": "ExternalLinkPay"`), and the 3DS, airline and IDX examples are out of scope.
The public sandbox MerchantId/MerchantKey the pages print as defaults are not copied anywhere.
