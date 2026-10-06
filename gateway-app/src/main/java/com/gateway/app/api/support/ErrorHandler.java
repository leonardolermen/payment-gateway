package com.gateway.app.api.support;

import com.gateway.app.observability.Masker;
import com.gateway.app.security.UnauthenticatedException;
import com.gateway.billing.customer.CustomerExistsException;
import com.gateway.billing.order.OrderHasActivePaymentException;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.payment.card.CardDeclinedException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidTypeIdException;

/**
 * Turns the kernel's exceptions into {@link ProblemDetail}. {@link NotFoundException} is handled
 * before {@link DomainException} even though it extends it: order matters here because Spring picks
 * the most specific matching handler, but being explicit beats relying on that resolution.
 */
@RestControllerAdvice
public class ErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, e.code(), e.getMessage());
  }

  /**
   * The domain codes that are not a 422. ALREADY_PAID and the two capture conflicts are 409: the
   * resource is in a state the call cannot change (the cancel lost to the payer; the sale was
   * captured already, or is not an authorization). CARD_DECLINED has its own handler (402). The
   * billing codes are 409 for the same reason: a customer, order or subscription whose state the
   * call cannot change, or a concurrent write that won (CONFLICT). DELIVERY_NOT_REDELIVERABLE is
   * 409 too: the delivery is PENDING or DELIVERED, or its endpoint was deactivated. So are
   * DIVERGENCE_CLOSED (the row was already decided) and DISPUTE_ALREADY_OPEN (one dispute per
   * payment at a time). JOB_IN_FLIGHT and JOB_NOT_PENDING are 409: a worker holds the job inside
   * its lease, or it is already DONE or DEAD. JOB_NOT_RERUNNABLE is 409: run-now on a DONE job,
   * refused because not every handler is idempotent. CHECKOUT_ORDER_CLOSED is 410, not 409: for the
   * payer the link is gone, nothing they do changes that. {@code Map.ofEntries}: {@code Map.of}
   * stops at ten pairs.
   */
  private static final Map<String, HttpStatus> STATUS_BY_CODE =
      Map.ofEntries(
          Map.entry("ALREADY_PAID", HttpStatus.CONFLICT),
          Map.entry("CAPTURE_NOT_ALLOWED", HttpStatus.CONFLICT),
          Map.entry("ALREADY_CAPTURED", HttpStatus.CONFLICT),
          Map.entry("CUSTOMER_EXISTS", HttpStatus.CONFLICT),
          Map.entry("CUSTOMER_HAS_ACTIVE_SUBSCRIPTION", HttpStatus.CONFLICT),
          Map.entry("ORDER_CLOSED", HttpStatus.CONFLICT),
          Map.entry("CHECKOUT_ORDER_CLOSED", HttpStatus.GONE),
          Map.entry("ORDER_HAS_ACTIVE_PAYMENT", HttpStatus.CONFLICT),
          Map.entry("SUBSCRIPTION_NOT_ACTIVE", HttpStatus.CONFLICT),
          Map.entry("CONFLICT", HttpStatus.CONFLICT),
          Map.entry("DELIVERY_NOT_REDELIVERABLE", HttpStatus.CONFLICT),
          Map.entry("DIVERGENCE_CLOSED", HttpStatus.CONFLICT),
          Map.entry("DISPUTE_ALREADY_OPEN", HttpStatus.CONFLICT),
          Map.entry("JOB_IN_FLIGHT", HttpStatus.CONFLICT),
          Map.entry("JOB_NOT_PENDING", HttpStatus.CONFLICT),
          Map.entry("JOB_NOT_RERUNNABLE", HttpStatus.CONFLICT));

  @ExceptionHandler(DomainException.class)
  public ProblemDetail domainError(DomainException e) {
    HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_ENTITY);
    return problem(status, e.code(), e.getMessage());
  }

  /**
   * Spec §9: 402 with our decline_code, and the payment id — the payment exists, FAILED, and the
   * merchant needs it to reconcile. The detail is fixed; the issuer's text never reaches here.
   */
  @ExceptionHandler(CardDeclinedException.class)
  public ProblemDetail cardDeclined(CardDeclinedException e) {
    ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, e.code(), e.getMessage());
    problem.setProperty("decline_code", e.declineCode());
    problem.setProperty("payment_id", e.paymentId());
    return problem;
  }

  /** The existing id, so the merchant uses it instead of retrying the create. */
  @ExceptionHandler(CustomerExistsException.class)
  public ProblemDetail customerExists(CustomerExistsException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    problem.setProperty("customer_id", e.customerId());
    return problem;
  }

  /** The attempt still open, so the merchant can cancel it or wait for it instead of guessing. */
  @ExceptionHandler(OrderHasActivePaymentException.class)
  public ProblemDetail orderHasActivePayment(OrderHasActivePaymentException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    problem.setProperty("payment_id", e.paymentId());
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
  }

  /**
   * A query or path parameter that does not convert ({@code ?status=NOPE}, a malformed UUID or
   * instant). Spring's own message names the Java type, so the detail is built from the parameter
   * name the client sent instead.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail parameterTypeMismatch(MethodArgumentTypeMismatchException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getName() + " is not valid");
  }

  /**
   * A body Jackson could not read. The type id is the one case with a message of its own, because
   * "method must be PIX, BOLECODE or CARD" is what this API answers and an error message is
   * contract — the create body became polymorphic on {@code method}, so an unknown one now fails in
   * the deserialiser instead of in a validate(). An error message is contract: it gained CARD with
   * the card method (plan 2026-09-28, Global Constraints).
   *
   * <p>Everything else gets a fixed detail: Jackson's own text carries class names and a slice of
   * the body, which are ours to read in the log and not the merchant's.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
    if (unknownTypeId(e)) {
      return problem(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "method must be PIX, BOLECODE or CARD");
    }

    log.info("unreadable request body: {}", Masker.mask(e.getMessage()));
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "the request body could not be read");
  }

  private static boolean unknownTypeId(HttpMessageNotReadableException e) {
    for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof InvalidTypeIdException) {
        return true;
      }
    }

    return false;
  }

  /**
   * Thrown by {@code MerchantContext.current()} when there is no authenticated merchant on the
   * request.
   */
  @ExceptionHandler(UnauthenticatedException.class)
  public ProblemDetail unauthenticated(UnauthenticatedException e) {
    return problem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", e.getMessage());
  }

  /**
   * A safety net only: {@code PaymentService} and {@code RefundService} already turn provider
   * failures into {@link DomainException}s with a merchant-facing code. One escaping here means a
   * path that forgot to; the detail stays fixed because the message may carry the bank's error
   * body, which is ours to read (masked, in the log) and not the merchant's.
   */
  @ExceptionHandler(ProviderException.class)
  public ProblemDetail providerError(ProviderException e) {
    log.error(
        "unhandled provider error {} (http {}): {}",
        e.code(),
        e.httpStatus(),
        Masker.mask(e.getMessage()));
    return problem(
        HttpStatus.BAD_GATEWAY,
        "PROVIDER_ERROR",
        "the payment provider could not complete the request");
  }

  /**
   * Spring's own web exceptions (wrong Content-Type, missing parameter, unmapped route, wrong
   * method, ...) all implement {@link ErrorResponse}. They need a handler here because the generic
   * {@code Exception} handler below runs before DefaultHandlerExceptionResolver and would turn each
   * of them into a 500. Anything that reaches Spring's BasicErrorController instead answers with a
   * "path" holding the full request URI, which on the public checkout carries the token, so the
   * detail is fixed per status and never e.getMessage(), which can carry that path too.
   */
  @ExceptionHandler(ErrorResponse.class)
  public ResponseEntity<ProblemDetail> springWebError(ErrorResponse e) {
    HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
    String code =
        switch (status) {
          case BAD_REQUEST -> "INVALID_REQUEST";
          case NOT_FOUND -> "NOT_FOUND";
          case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
          case NOT_ACCEPTABLE -> "NOT_ACCEPTABLE";
          case UNSUPPORTED_MEDIA_TYPE -> "UNSUPPORTED_MEDIA_TYPE";
          default -> status.name();
        };
    String detail =
        switch (status) {
          case BAD_REQUEST -> "the request is not valid";
          case NOT_FOUND -> "no such route";
          case METHOD_NOT_ALLOWED -> "method not allowed";
          case NOT_ACCEPTABLE -> "no acceptable representation";
          case UNSUPPORTED_MEDIA_TYPE -> "unsupported media type";
          default -> status.getReasonPhrase().toLowerCase(Locale.ROOT);
        };

    return ResponseEntity.status(status)
        .headers(e.getHeaders())
        .body(problem(status, code, detail));
  }

  /** Last resort; every more specific handler above wins over it. */
  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    log.error("unexpected error", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error");
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setType(URI.create("urn:gateway:" + code));
    return problemDetail;
  }
}
