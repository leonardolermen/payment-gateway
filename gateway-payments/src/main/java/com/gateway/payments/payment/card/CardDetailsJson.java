package com.gateway.payments.payment.card;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled codec for {@code details.card}, the style of PixDetailsJson and BoletoDetailsJson: no
 * Jackson in the domain, and it only reads back what {@link #write} produced (plus the space
 * Postgres adds after each colon). Keys are disjoint from the pix and boleto ones
 * (PaymentDetailsJsonTest pins it), so the reader scans the whole document.
 */
public final class CardDetailsJson {
  private static final Pattern HAS_CARD = Pattern.compile("\"card\":\\s*\\{");

  private CardDetailsJson() {}

  public static String write(CardDetails card) {
    if (card == null) {
      return "null";
    }

    return "{\"paymentId\":"
        + text(card.paymentId())
        + ",\"tid\":"
        + text(card.tid())
        + ",\"authorizationCode\":"
        + text(card.authorizationCode())
        + ",\"proofOfSale\":"
        + text(card.proofOfSale())
        + ",\"brand\":"
        + text(card.brand())
        + ",\"last4\":"
        + text(card.last4())
        + ",\"installments\":"
        + card.installments()
        + ",\"capturedAmount\":"
        + (card.capturedAmount() == null ? "null" : card.capturedAmount())
        + ",\"cardId\":"
        + text(card.cardId())
        + ",\"declineCode\":"
        + text(card.declineCode())
        + ",\"interestAmount\":"
        + card.interestAmount()
        + "}";
  }

  /** Null when the document has no card object (a Pix or a Bolecode payment). */
  public static CardDetails read(String details) {
    if (details == null || !HAS_CARD.matcher(details).find()) {
      return null;
    }

    Long installments = number(details, "installments");
    // Absent in a payment written before interest existed: it had none.
    Long interestAmount = number(details, "interestAmount");
    return new CardDetails(
        field(details, "paymentId"),
        field(details, "tid"),
        field(details, "authorizationCode"),
        field(details, "proofOfSale"),
        field(details, "brand"),
        field(details, "last4"),
        installments == null ? 1 : installments.intValue(),
        number(details, "capturedAmount"),
        field(details, "cardId"),
        field(details, "declineCode"),
        interestAmount == null ? 0 : interestAmount);
  }

  /**
   * Every value written here is an id, a code or four digits: nothing needs escaping but quotes.
   */
  private static String text(String value) {
    return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String field(String json, String key) {
    Matcher matcher =
        Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!matcher.find() || matcher.group(2) == null) {
      return null;
    }

    return matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private static Long number(String json, String key) {
    Matcher matcher = Pattern.compile("\"" + key + "\":\\s*(null|-?\\d+)").matcher(json);
    if (!matcher.find() || "null".equals(matcher.group(1))) {
      return null;
    }

    return Long.parseLong(matcher.group(1));
  }
}
