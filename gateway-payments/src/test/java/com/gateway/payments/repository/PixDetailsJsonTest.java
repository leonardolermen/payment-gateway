package com.gateway.payments.repository;

import static org.assertj.core.api.Assertions.*;

import com.gateway.payments.domain.PixDetails;
import org.junit.jupiter.api.Test;

class PixDetailsJsonTest {
  @Test
  void roundTrips() {
    PixDetails pix = new PixDetails("id1", "000201...copia-e-cola", "pix.example.com/loc", null);
    String json = PixDetailsJson.write(pix);
    System.out.println("JSON=" + json);
    PixDetails back = PixDetailsJson.read(json);
    assertThat(back).isEqualTo(pix);
  }
}
