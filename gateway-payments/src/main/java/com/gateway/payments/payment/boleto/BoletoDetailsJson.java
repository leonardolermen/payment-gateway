package com.gateway.payments.payment.boleto;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled codec for {@code details.boleto}, the same style as {@code PixDetailsJson}: no
 * Jackson in the domain, and it only reads back what {@link #write} produced. Keys are chosen not
 * to collide with the pix keys, so both readers can scan the whole {@code details} document.
 */
public final class BoletoDetailsJson {
  private BoletoDetailsJson() {}

  public static String write(BoletoDetails b) {
    if (b == null) {
      return "null";
    }
    return "{\"nossoNumero\":" + str(b.nossoNumero())
        + ",\"idBoletoIndividual\":" + str(b.idBoletoIndividual())
        + ",\"linhaDigitavel\":" + str(b.linhaDigitavel())
        + ",\"codigoBarras\":" + str(b.codigoBarras())
        + ",\"dueDate\":" + str(b.dueDate() == null ? null : b.dueDate().toString())
        + ",\"paymentLimitDate\":" + str(b.paymentLimitDate() == null ? null : b.paymentLimitDate().toString())
        + ",\"paidVia\":" + str(b.paidVia() == null ? null : b.paidVia().name())
        + "}";
  }

  /** Null for {@code null}, an absent block, or a document without {@code nossoNumero} (a Pix payment). */
  public static BoletoDetails read(String json) {
    if (json == null || json.isBlank() || json.trim().equals("null")) {
      return null;
    }
    String nossoNumero = field(json, "nossoNumero");
    if (nossoNumero == null) {
      return null;
    }
    String via = field(json, "paidVia");
    return new BoletoDetails(nossoNumero, field(json, "idBoletoIndividual"), field(json, "linhaDigitavel"), field(json, "codigoBarras"),
        date(field(json, "dueDate")), date(field(json, "paymentLimitDate")), via == null ? null : PaidVia.valueOf(via));
  }

  private static LocalDate date(String s) {
    return s == null ? null : LocalDate.parse(s);
  }

  // \s* after ':' because Postgres reformats jsonb on the way out (see PixDetailsJson).
  private static String field(String json, String key) {
    Matcher matcher = Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(2) == null ? null : unescape(matcher.group(2));
  }

  private static String str(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder stringBuilder = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> stringBuilder.append("\\\"");
        case '\\' -> stringBuilder.append("\\\\");
        case '\n' -> stringBuilder.append("\\n");
        case '\r' -> stringBuilder.append("\\r");
        case '\t' -> stringBuilder.append("\\t");
        default -> stringBuilder.append(c);
      }
    }
    return stringBuilder.append('"').toString();
  }

  private static String unescape(String s) {
    StringBuilder stringBuilder = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case 'n' -> stringBuilder.append('\n');
          case 'r' -> stringBuilder.append('\r');
          case 't' -> stringBuilder.append('\t');
          default -> stringBuilder.append(next);
        }
      } else {
        stringBuilder.append(c);
      }
    }
    return stringBuilder.toString();
  }
}
