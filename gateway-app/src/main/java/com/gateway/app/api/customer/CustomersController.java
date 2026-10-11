package com.gateway.app.api.customer;

import com.gateway.app.api.card.dto.CardResponse;
import com.gateway.app.api.customer.dto.CustomerPatchRequest;
import com.gateway.app.api.customer.dto.CustomerRequest;
import com.gateway.app.api.customer.dto.CustomerResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerService;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling merchant's customers. A customer belongs to the API key's environment like a payment
 * does, so the same document may exist once under TEST and once under LIVE.
 */
@RestController
@RequestMapping("/v1/customers")
public class CustomersController {
  private static final int MAX_PAGE = 100;

  private final CustomerService customers;
  private final Clock clock;

  public CustomersController(CustomerService customers, Clock clock) {
    this.customers = customers;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<CustomerResponse> create(@RequestBody CustomerRequest request) {
    MerchantContext.Current caller = MerchantContext.current();

    Customer customer =
        request.toCustomer(
            caller.merchantId(), Environments.toProvider(caller.environment()), clock);
    Customer created = customers.create(customer);

    // The header is read and stripped by IdempotencyFilter; clients never see it.
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, created.id())
        .body(CustomerResponse.from(created));
  }

  @GetMapping("/{id}")
  public CustomerResponse get(@PathVariable String id) {
    return CustomerResponse.from(customers.get(MerchantContext.current().merchantId(), id));
  }

  /**
   * Without {@code document}: the active customers of the key's environment, newest first, by
   * cursor (the last id of the previous page). With it: a list of zero or one, not a 404, because
   * the NOT_FOUND message names what was looked up, and that would echo the full document back into
   * a response and its logs.
   */
  @GetMapping
  public List<CustomerResponse> list(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String document,
      @RequestParam(required = false) String q) {
    if (limit <= 0 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }

    MerchantContext.Current caller = MerchantContext.current();

    if (document == null) {
      return listOrSearch(caller, q, cursor, limit).stream().map(CustomerResponse::from).toList();
    }

    if (cursor != null || q != null) {
      throw new IllegalArgumentException("document cannot be combined with cursor or q");
    }

    // Document.of throws InvalidValue, which no handler maps: unconverted it would be a 500.
    try {
      return customers
          .findByDocument(
              caller.merchantId(), Environments.toProvider(caller.environment()), document)
          .map(CustomerResponse::from)
          .stream()
          .toList();
    } catch (InvalidValue invalid) {
      throw new IllegalArgumentException("document " + invalid.reason());
    }
  }

  /** The panel's search box: by part of the name, or the plain page when nothing was typed. */
  private List<Customer> listOrSearch(
      MerchantContext.Current caller, String q, String cursor, int limit) {
    ProviderEnvironment environment = Environments.toProvider(caller.environment());

    if (q == null || q.isBlank()) {
      return customers.list(caller.merchantId(), environment, cursor, limit);
    }

    return customers.searchByName(caller.merchantId(), environment, q.trim(), cursor, limit);
  }

  @PatchMapping("/{id}")
  public CustomerResponse update(
      @PathVariable String id, @RequestBody CustomerPatchRequest request) {
    request.validate();

    Customer updated =
        customers.update(
            MerchantContext.current().merchantId(),
            id,
            request.name(),
            request.email(),
            request.address() == null ? null : request.address().toRaw());

    return CustomerResponse.from(updated);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    customers.delete(MerchantContext.current().merchantId(), id);
  }

  @GetMapping("/{id}/cards")
  public List<CardResponse> cards(@PathVariable String id) {
    return customers.cardsOf(MerchantContext.current().merchantId(), id).stream()
        .map(CardResponse::from)
        .toList();
  }
}
