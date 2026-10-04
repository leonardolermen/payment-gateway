package com.gateway.billing.customer;

import com.gateway.kernel.party.Document;

/** What a response shows: enough to recognise, not enough to reuse. */
public final class DocumentMask {
  private DocumentMask() {}

  public static String mask(Document document) {
    String digits = document.digits();
    if (document.isCompany()) {
      return "**.***.***/" + digits.substring(8, 12) + "-" + digits.substring(12);
    }

    return "***." + digits.substring(3, 6) + ".***-" + digits.substring(9);
  }
}
