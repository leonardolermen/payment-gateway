package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.checkout.CheckoutLinks;
import com.gateway.billing.order.checkout.CheckoutToken;
import com.gateway.billing.order.checkout.CheckoutTokens;
import com.gateway.billing.order.persistence.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The link of an invoice (spec 2026-10-07 §3). An invoice is born with a token like any order, and
 * the url leaves once, in the event that announces the invoice; the row keeps only the hash, so an
 * event that has to announce the invoice again (a resumed cycle, a dunning reissue) rotates the
 * token and carries the new url. The plain token never reaches a billing table or a log line.
 */
public class InvoiceLinks {
  private static final Logger log = LoggerFactory.getLogger(InvoiceLinks.class);

  private final CheckoutTokens tokens;
  private final CheckoutLinks links;
  private final OrderRepository orders;

  public InvoiceLinks(CheckoutTokens tokens, CheckoutLinks links, OrderRepository orders) {
    this.tokens = tokens;
    this.links = links;
    this.orders = orders;
  }

  /** For an invoice about to be created: its hash goes into the order, its token into the event. */
  public CheckoutTokens.Issued issue() {
    return tokens.issue();
  }

  public String urlFor(CheckoutToken token) {
    return links.urlFor(token);
  }

  /**
   * Requires a transaction. A new token for an open invoice, and its url; null when the invoice is
   * closed (a dead order gets no link) or changed under us. Never throws: the caller is booking
   * what the bank did, and a link the merchant can rotate by hand is not worth losing that for.
   */
  public String reissue(Order invoice, Instant at) {
    Optional<Order> found = orders.findById(invoice.id());
    if (found.isEmpty() || !found.get().isOpen()) {
      return null;
    }

    Order current = found.get();
    CheckoutTokens.Issued issued = tokens.issue();
    current.rotateCheckoutToken(issued.hash(), at);
    if (!orders.update(current)) {
      log.warn(
          "invoice {} changed while reissuing its link; the event goes without one", invoice.id());
      return null;
    }

    return links.urlFor(issued.token());
  }
}
