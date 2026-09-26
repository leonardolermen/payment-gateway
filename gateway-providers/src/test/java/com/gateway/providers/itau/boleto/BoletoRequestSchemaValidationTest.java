package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Address;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.Payer;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What we send must satisfy the bank's own schema. Same mechanics as the Pix test: the validator is
 * pointed at the whole file by URI + JSON-pointer fragment so {@code $ref}s resolve; networknt is
 * Jackson 2, so the body crosses over as a string.
 */
class BoletoRequestSchemaValidationTest {
  static final tools.jackson.databind.ObjectMapper M3 = new tools.jackson.databind.ObjectMapper();
  static final com.fasterxml.jackson.databind.ObjectMapper M2 =
      new com.fasterxml.jackson.databind.ObjectMapper();
  static final Path OPENAPI = Path.of("src/test/resources/itau/boleto/issue.openapi.json");

  static JsonSchema schemaOf(String component) {
    String uri = OPENAPI.toAbsolutePath().toUri() + "#/components/schemas/" + component;
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
        .getSchema(SchemaLocation.of(uri), SchemaValidatorsConfig.builder().build());
  }

  static Set<ValidationMessage> validate(Object body) throws Exception {
    return schemaOf("boletoPix").validate(M2.readTree(M3.writeValueAsString(body)));
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse(
        "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}"
            .getBytes());
  }

  static BoletoIssueRequest request(String document) {
    return new BoletoIssueRequest(
        "00000042",
        Money.brl(123456),
        LocalDate.of(2026, 12, 31),
        LocalDate.of(2027, 1, 30),
        new Payer(
            PersonName.of("João da Silva"),
            Document.of(document),
            new Address(
                "Rua das Flores, 10", "Centro", "São Paulo", Uf.of("SP"), ZipCode.of("01310100"))),
        "Pedido 42");
  }

  @Test
  void issueBodyWithCpfIsValid() throws Exception {
    assertThat(validate(BoletoPixRequest.forIssue(request("12345678901"), creds()))).isEmpty();
  }

  @Test
  void issueBodyWithCnpjIsValid() throws Exception {
    assertThat(validate(BoletoPixRequest.forIssue(request("12345678000190"), creds()))).isEmpty();
  }

  @Test
  void sanitizedTextsStillValidate() throws Exception {
    BoletoIssueRequest dirty =
        new BoletoIssueRequest(
            "00000042",
            Money.brl(100),
            LocalDate.of(2026, 12, 31),
            null,
            new Payer(
                PersonName.of("Ana & Cia (Ltda)"),
                Document.of("12345678901"),
                new Address(
                    "Av. Paulista, 1000 / 10º",
                    "Bela Vista <x>",
                    "São Paulo",
                    Uf.of("SP"),
                    ZipCode.of("01310100"))),
            "http://x alert #1");
    assertThat(validate(BoletoPixRequest.forIssue(dirty, creds()))).isEmpty();
  }

  /** Guards the guard: the schema must reject an obviously wrong body. */
  @Test
  void theSchemaRejectsABadAmount() throws Exception {
    BoletoPixRequest body = BoletoPixRequest.forIssue(request("12345678901"), creds());
    var node =
        (com.fasterxml.jackson.databind.node.ObjectNode) M2.readTree(M3.writeValueAsString(body));
    ((com.fasterxml.jackson.databind.node.ObjectNode)
            node.at("/dado_boleto/dados_individuais_boleto/0"))
        .put("valor_titulo", "1234");
    assertThat(schemaOf("boletoPix").validate(node)).isNotEmpty();
  }
}
