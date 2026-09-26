package com.gateway.app.api.support;

import com.gateway.app.observability.Masker;
import com.gateway.app.security.UnauthenticatedException;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.provider.ProviderException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
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
   * ALREADY_PAID is a conflict, not a validation error: the cancel lost to the payer and the
   * resource moved to COMPLETED.
   */
  @ExceptionHandler(DomainException.class)
  public ProblemDetail domainError(DomainException e) {
    HttpStatus status =
        "ALREADY_PAID".equals(e.code()) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
    return problem(status, e.code(), e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
  }

  /**
   * A body Jackson could not read. The type id is the one case with a message of its own, because
   * "method must be PIX or BOLECODE" is what this API has always answered and an error message is
   * contract — the create body became polymorphic on {@code method}, so an unknown one now fails in
   * the deserialiser instead of in a validate().
   *
   * <p>Everything else gets a fixed detail: Jackson's own text carries class names and a slice of
   * the body, which are ours to read in the log and not the merchant's.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
    if (unknownTypeId(e)) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "method must be PIX or BOLECODE");
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

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
    problemDetail.setType(URI.create("urn:gateway:" + code));
    return problemDetail;
  }
}
