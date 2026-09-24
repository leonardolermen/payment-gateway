# Plan B: final review fix report

Branch `feat/plano-b`, from 8f9117f. Commits:
- `5791f90` fix(providers): fingerprint the whole itau credential and wait 30 s for the bank (5, 8, 10)
- `96b3858` fix(payments): the bank confirms every webhook, refunds keep their reserve (1, 2, 3, 4, 6, 7, 9, 11)
- docs commit: DECISOES.md entries (12) and this report

## Per finding

**1. Webhook is a hint (critical).**
(a) `WebhookInboxService.process` calls `PaymentService.settleFromWebhook`, which runs `findCharge`. It completes only when the bank reports `COMPLETED` with a `ReceivedPix` whose e2eid matches the webhook's. The bank's pix, not the body's, goes on to `settle`. If the bank does not confirm, the payment gets an `ignored` event plus an `UNCONFIRMED_WEBHOOK` divergence, and nothing is emitted. If the bank is unreachable, `ProviderException` propagates and the job retries. `Payment.recordIgnored` now also accepts PENDING/EXPIRED; only CREATED is refused.
(b) `settle` refuses a PENDING/EXPIRED payment whose pix amount differs from `payment.amount()`. It records an `ignored` event and an `AMOUNT_MISMATCH` divergence, and emits nothing.
(c) `ReconciliationService`: COMPLETED here while the bank status is not COMPLETED opens a divergence with the bank status. This replaces the old removed-only branch and now also covers ACTIVE. COMPLETED where the bank's pix[] lacks our e2eid also opens one, with providerStatus `COMPLETED`.
Tests: `WebhookInboxServiceIntegrationTest.aForgedWebhookTheBankDoesNotConfirmCompletesNothing`, `aWebhookWhoseEndToEndIdTheBankDoesNotHaveCompletesNothing`, `aConfirmedPixOfTheWrongAmountOpensADivergenceInsteadOfCompleting`, `theBankUnreachableLeavesTheRowForTheRetry`, `processCompletesThePendingPaymentTheBankConfirms`; `ExpirationAndReconciliationIntegrationTest.reconciliationFlagsACompletedPaymentTheBankStillHasActive`, `reconciliationFlagsACompletedPaymentWhoseEndToEndIdTheBankDoesNotKnow`; `PaymentTest.unconfirmedWebhookOnPendingIsRecordedWithoutATransition`. The app tests (`PaymentsFlowIntegrationTest`, `ItauWebhookMtlsIntegrationTest`) now stub `GET /cob/{txid}` with a CONCLUIDA fixture (`get_cob_200_completed.json`, copied into gateway-app).

**2. Refund UNAVAILABLE is not FAILED (critical).** `RefundService.request`: only INVALID/DECLINED/NOT_FOUND fail at once (`definitelyRefused`). Every other code, including TIMEOUT and UNAVAILABLE, leaves the refund PROCESSING with the POLL_REFUND job. `RefundPollingService.poll` fails a refund the bank still does not know after `refundNotFoundGrace` (new, default PT30M, also in application.yml). Test: `RefundServiceIntegrationTest.unavailableOnThePutIsProcessingAndPollingFailsItOnceTheBankNeverSawIt`.

**3. giveUp keeps the reservation.** New `RefundState.UNKNOWN` and `Refund.markUnknown`, allowed only from REQUESTED/PROCESSING. UNKNOWN can still move to COMPLETED/FAILED. `giveUp` does markUnknown, emits `refund.unknown` and opens the `REFUND_UNKNOWN` divergence in one transaction, holding the payment row lock. The reserve sum still excludes only FAILED. Polling treats UNKNOWN as terminal. The `PaymentEvents.refundJson` javadoc documents the state. Tests: `RefundTest.unknownKeepsTheReasonAndCanStillSettleEitherWay`, `RefundServiceIntegrationTest.pollingThatNeverSettlesEndsUnknownKeepingTheReserve` (the reserve is still held, then a later COMPLETED applies).

**4. Refund webhook updates scoped and confirmed.** `ProviderWebhookEvent` gains `endToEndIdByRefundId`, filled by `ItauPixProvider.parseWebhook`; a 3-argument constructor is kept. `RefundService.confirmFromWebhook` requires three things: the refund's merchant equals the inbox merchant, the item's e2eid equals the payment's `pix().endToEndId()`, and then `findRefund`, whose result is applied. If the merchant or e2eid does not match, an `UNCONFIRMED_REFUND_WEBHOOK` divergence opens on the refund's payment and the row is not counted as matched. Tests: `aRefundUpdateForAnotherMerchantsRefundIsIgnoredWithADivergence`, `aRefundUpdateUnderAnotherPixIsIgnored`, `aRefundUpdateAppliesWhatTheBankSaysNotTheBody`; `ItauPixProviderTest.parseWebhook` asserts the new map.

**5. Credential fingerprint.** `ItauCredentials` gains a `fingerprint` component: the SHA-256 hex of the whole payload bytes, computed in `parse`. `toString` leaves it out. The `ItauTokenClient` javadoc is updated. Test: `fingerprintChangesWhenAnyFieldChanges` (it also checks that toString does not contain the fingerprint).

**6. 409 text + reference filter.** `IdempotencyFilter` uses the required detail text. `GET /v1/payments?reference=` goes through `PaymentService.listByReference` and `PaymentRepository.listByMerchantAndReference`, newest first. Combining it with `cursor` returns 400. V202 adds an index on `(merchant_id, reference)`. Test: step 7b of `PaymentsFlowIntegrationTest` (a hit and an empty result).

**7. No bank text to the merchant.** New `ProviderErrors` has one fixed message per code (PROVIDER_DECLINED/UNAVAILABLE/TIMEOUT) and one log line with the bank text; the app's masking encoder handles that line. It is used by createCharge, cancel and refund. A refund's `failureReason` on an immediate refusal is the fixed text, so the refund JSON and webhook no longer carry bank text. The bank text stays in `provider_requests` (ProviderGateway is unchanged). Tests: `PaymentServiceIntegrationTest.providerDeclineMarksFailed` (hasMessage), `RefundServiceIntegrationTest.aRefusedPutFailsAtOnceWithAFixedMessage`.

**8.** `gateway.providers.itau.read-timeout: PT30S`.

**9.** `V201__divergence_unique_open.sql` removes existing duplicate OPEN rows (keeping the oldest), then adds the partial unique index. `ReconciliationDivergenceRepository.openIfAbsent` does a native `INSERT ... ON CONFLICT DO NOTHING` under `@Transactional`. `openDivergence` no longer loads the OPEN rows. `PaymentsProperties.reconcileLease` (PT10M) is applied by `selectDue` only to RECONCILE; a 3-argument `claimDue` default is kept. Tests: `theDatabaseRefusesASecondOpenDivergenceForTheSameMismatch`, `theReconcileJobHasItsOwnLongerLease`; the existing `reconciliationOpensDivergenceForTheRest` still checks that a second run does not duplicate.

**10.** A blank `private_key_pem` (and `certificate_pem`) is normalized to null in `parse`. Test: `blankPrivateKeyCountsAsMissing`.

**11.** `gateway-app/.../api/dto/PaymentJsonContractTest`: the top-level and `pix` key sets of the SNAKE_CASE-serialized `PaymentResponse` equal those of `PaymentEvents.paymentJson`. `paymentJson` was made public for this.

**12.** DECISOES.md: "Webhook é gatilho, o banco é a verdade" and "UNKNOWN mantém a reserva da devolução", each with the rejected alternative and its cost.

## Full suite

`export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1" && ./mvnw -B test` (foreground): BUILD SUCCESS, **451 tests, 0 failures, 0 errors, 0 skipped**. Per module: kernel 12, merchants 18, providers 66, payments 299, app 56. The baseline was 434.

## Deviations

- 1(a)/(b): the webhook-path confirmation matches on status and e2eid. The amount check is done by `settle` (b), so a confirmed Pix with the wrong amount opens `AMOUNT_MISMATCH`, not `UNCONFIRMED_WEBHOOK`. Either way nothing completes. Confirmation also runs for payments that are already terminal, so a forged POST cannot open `PIX_RECEIVED` divergences on FAILED/CANCELED payments either.
- 1(c): an e2eid mismatch uses providerStatus `COMPLETED`, the bank's status as asked. It shares the unique-index slot with the existing amount-differs divergence, so while OPEN only the first of the two is kept.
- 2: UNAUTHENTICATED/UNKNOWN codes on the refund PUT now also go PROCESSING, per "only INVALID/DECLINED/NOT_FOUND fail immediately". A NOT_FOUND on the PUT maps to PROVIDER_DECLINED.
- 4: a refund id that is not in our database only logs, because there is no payment to hang a divergence on.
- 6: the V202 index was added although it was not requested. No test drives the 409 text itself, because that needs an interrupted request.
- 11: the test builds a SNAKE_CASE JsonMapper that mirrors application.yml instead of loading the Spring context.
- Refund failureReason from a bank `motivo` on a settled NAO_REALIZADO is still passed through. That is the refund outcome, not error text.

## Concerns

- V201 deletes duplicate OPEN divergences before building the index. That is harmless on a fresh database; on one with data it removes later identical copies.
- The fake bank (`RecordingPixProvider`) now drives most webhook tests through `markPaid`. The Itau-specific parsing of the confirmation (`GET /cob` CONCLUIDA) is covered only by the two app WireMock tests.
