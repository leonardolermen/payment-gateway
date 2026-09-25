package com.gateway.payments.payment.pix;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PixDetailsJsonTest {
  @Test
  void roundTrips() {
    PixDetails pix = new PixDetails("id1", "000201...copia-e-cola", "pix.example.com/loc", null);
    String json = PixDetailsJson.write(pix);
    PixDetails back = PixDetailsJson.read(json);
    assertThat(back).isEqualTo(pix);
  }
}
