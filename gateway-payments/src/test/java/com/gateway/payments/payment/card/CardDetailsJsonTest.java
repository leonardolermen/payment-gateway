package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardDetailsJsonTest {
  static final CardDetails FULL =
      new CardDetails(
          "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
          "1124060407175",
          "663864",
          "182738",
          "VISA",
          "3171",
          3,
          6000L,
          "01K0CARDID0000000000000000",
          null,
          605);

  @Test
  void roundTrips() {
    assertThat(CardDetailsJson.read("{\"card\":" + CardDetailsJson.write(FULL) + "}"))
        .isEqualTo(FULL);
  }

  /** Postgres adds a space after every colon on the way back out of jsonb (see PixDetailsJson). */
  @Test
  void readsWhatPostgresGivesBack() {
    String fromJsonb =
        "{\"card\": {\"tid\": \"1124060407175\", \"brand\": \"VISA\", \"last4\": \"3171\", \"cardId\": null,"
            + " \"paymentId\": \"6f8d1753-86bb-4dc0-9ebb-09a29093e1fb\", \"declineCode\": \"GENERIC\","
            + " \"proofOfSale\": null, \"installments\": 1, \"capturedAmount\": null,"
            + " \"authorizationCode\": null}}";

    CardDetails read = CardDetailsJson.read(fromJsonb);

    assertThat(read.paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
    assertThat(read.installments()).isEqualTo(1);
    assertThat(read.capturedAmount()).isNull();
    assertThat(read.declineCode()).isEqualTo("GENERIC");
    // Written before interest existed: no interestAmount key, and that payment had none.
    assertThat(read.interestAmount()).isZero();
  }

  @Test
  void readsTheInterestBackFromJsonb() {
    String fromJsonb =
        "{\"card\": {\"brand\": \"VISA\", \"installments\": 6, \"interestAmount\": 1076,"
            + " \"capturedAmount\": null}}";

    assertThat(CardDetailsJson.read(fromJsonb).interestAmount()).isEqualTo(1076);
  }

  @Test
  void noCardBlockIsNull() {
    assertThat(CardDetailsJson.read("{\"pix\":{\"txid\":\"t\"},\"boleto\":null}")).isNull();
    assertThat(CardDetailsJson.read(null)).isNull();
  }

  /** The block never carries a number, an expiry or a CVV (spec §4): only these eleven keys. */
  @Test
  void writesExactlyTheElevenKeys() {
    assertThat(CardDetailsJson.write(FULL))
        .isEqualTo(
            "{\"paymentId\":\"6f8d1753-86bb-4dc0-9ebb-09a29093e1fb\",\"tid\":\"1124060407175\","
                + "\"authorizationCode\":\"663864\",\"proofOfSale\":\"182738\",\"brand\":\"VISA\","
                + "\"last4\":\"3171\",\"installments\":3,\"capturedAmount\":6000,"
                + "\"cardId\":\"01K0CARDID0000000000000000\",\"declineCode\":null,"
                + "\"interestAmount\":605}");
  }
}
