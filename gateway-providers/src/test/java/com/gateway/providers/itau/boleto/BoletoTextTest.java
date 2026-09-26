package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import org.junit.jupiter.api.Test;

/**
 * The bank's own list (issue OpenAPI, boletoPix.description): no [ : < > & ; ' " ` ( ) # * / | ü
 * and no http/javascript/alert.
 */
class BoletoTextTest {
  @Test
  void keepsAccentsAndDropsTheForbiddenCharacters() {
    assertThat(BoletoText.name("João da Silva", 50)).isEqualTo("João da Silva");
    assertThat(BoletoText.name("Ana <b>&amp; Cia (Ltda) / \"x\"", 50))
        .isEqualTo("Ana bamp Cia Ltda x");
    assertThat(BoletoText.text("Rua das Flores, 10 - apto 3; #2", 45))
        .isEqualTo("Rua das Flores, 10 - apto 3 2");
    assertThat(BoletoText.text("Müller", 45)).isEqualTo("Mller");
  }

  @Test
  void namesHaveNoDigitsButAddressesDo() {
    assertThat(BoletoText.name("Loja 24h", 50)).isEqualTo("Loja h");
    assertThat(BoletoText.text("Loja 24h", 45)).isEqualTo("Loja 24h");
  }

  @Test
  void stripsTheForbiddenWordsAndTruncates() {
    assertThat(BoletoText.text("http://evil javascript alert Pedido 42", 45))
        .isEqualTo("evil Pedido 42");
    assertThat(BoletoText.text("x".repeat(60), 25)).hasSize(25);
    assertThat(BoletoText.text("  a   b  ", 45)).isEqualTo("a b");
  }

  @Test
  void nothingUsableIsInvalid() {
    assertThatThrownBy(() -> BoletoText.name("<>()", 50))
        .isInstanceOfSatisfying(
            ProviderException.class,
            e -> assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID));
    assertThat(BoletoText.text(null, 45)).isNull();
  }
}
