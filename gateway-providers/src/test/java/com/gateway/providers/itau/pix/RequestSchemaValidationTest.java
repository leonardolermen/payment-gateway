package com.gateway.providers.itau.pix;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.providers.itau.pix.dto.CobRequest;
import com.gateway.providers.itau.pix.dto.DevolucaoRequest;
import com.networknt.schema.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What we send must satisfy Itaú's own schema. This is the test that replaces the sandbox until we
 * have one.
 *
 * <p>The component schemas {@code $ref} each other ({@code devedor → #/components/schemas/pessoa}),
 * so validating the extracted sub-node alone cannot resolve them. We point the validator at the
 * whole file by URI with a JSON-pointer fragment: refs then resolve against the document root.
 * networknt is Jackson 2, so the body crosses over as a JSON string.
 *
 * <p>Itaú's {@code pessoa} schema has two defects, measured by this test's first run: it requires
 * {@code cpf} AND {@code cnpj} (Bacen's own spec has {@code oneOf PessoaFisica/PessoaJuridica}, each
 * with one document), and the cpf/cnpj patterns keep JavaScript slashes ({@code "/^\d{11}$/"}),
 * so no real document can match. Validating against it verbatim would reject every body with a
 * payer. We validate against a copy with exactly those two fixes, and {@link
 * #itauPessoaSchemaStillHasTheKnownDefects} pins the defects so the patch is revisited the day the
 * bank republishes the file.
 */
class RequestSchemaValidationTest {
  static final tools.jackson.databind.ObjectMapper M3 = new tools.jackson.databind.ObjectMapper();
  static final com.fasterxml.jackson.databind.ObjectMapper M2 = new com.fasterxml.jackson.databind.ObjectMapper();

  static final Path OPENAPI = Path.of("src/test/resources/itau/openapi.json");
  static Path patched;

  static synchronized Path patchedOpenApi() throws Exception {
    if (patched != null) {
      return patched;
    }
    var api = (com.fasterxml.jackson.databind.node.ObjectNode) M2.readTree(Files.readString(OPENAPI));
    var pessoa = (com.fasterxml.jackson.databind.node.ObjectNode) api.at("/components/schemas/pessoa");
    var props = (com.fasterxml.jackson.databind.node.ObjectNode) pessoa.get("properties");
    ((com.fasterxml.jackson.databind.node.ObjectNode) props.get("cpf")).put("pattern", "^\\d{11}$");
    ((com.fasterxml.jackson.databind.node.ObjectNode) props.get("cnpj")).put("pattern", "^\\d{14}$");
    pessoa.putArray("required").add("nome");
    var oneOf = pessoa.putArray("oneOf");
    oneOf.addObject().putArray("required").add("cpf");
    oneOf.addObject().putArray("required").add("cnpj");
    Path tmp = Files.createTempFile("itau-openapi-patched", ".json");
    tmp.toFile().deleteOnExit();
    Files.writeString(tmp, M2.writeValueAsString(api));
    return patched = tmp;
  }

  static JsonSchema schemaOf(String component) throws Exception {
    String uri = patchedOpenApi().toAbsolutePath().toUri() + "#/components/schemas/" + component;
    JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    return f.getSchema(SchemaLocation.of(uri), SchemaValidatorsConfig.builder().build());
  }

  @Test void itauPessoaSchemaStillHasTheKnownDefects() throws Exception {
    var pessoa = M2.readTree(Files.readString(OPENAPI)).at("/components/schemas/pessoa");
    assertThat(pessoa.at("/properties/cpf/pattern").asText()).isEqualTo("/^\\d{11}$/");
    assertThat(pessoa.at("/properties/cnpj/pattern").asText()).isEqualTo("/^\\d{14}$/");
    assertThat(pessoa.get("required").toString()).isEqualTo("[\"cpf\",\"cnpj\",\"nome\"]");
  }

  @Test void thePatchedSchemaRejectsAPayerWithBothDocuments() throws Exception {
    var body = M2.readTree("{\"calendario\":{\"expiracao\":3600},\"valor\":{\"original\":\"1.00\"},\"chave\":\"k\","
        + "\"devedor\":{\"cpf\":\"12345678909\",\"cnpj\":\"60701190000104\",\"nome\":\"x\"}}");
    assertThat(schemaOf("cobrancaImediataPutRequest").validate(body)).isNotEmpty();
  }

  static Set<ValidationMessage> validate(String component, Object body) throws Exception {
    return schemaOf(component).validate(M2.readTree(M3.writeValueAsString(body)));
  }

  @Test void createChargeBodyIsValid() throws Exception {
    CobRequest body = CobRequest.forCharge(Money.brl(15990), 3600, "60701190000104", "12345678909", "Jane Doe", "Order 8812");
    assertThat(validate("cobrancaImediataPutRequest", body)).isEmpty();
  }

  @Test void createChargeWithCnpjPayerIsValid() throws Exception {
    CobRequest body = CobRequest.forCharge(Money.brl(15990), 3600, "60701190000104", "60.701.190/0001-04", "Acme SA", "x".repeat(200));
    assertThat(validate("cobrancaImediataPutRequest", body)).isEmpty();
  }

  @Test void createChargeWithoutPayerIsValid() throws Exception {
    CobRequest body = CobRequest.forCharge(Money.brl(100), 3600, "60701190000104", null, null, null);
    assertThat(validate("cobrancaImediataPutRequest", body)).isEmpty();
  }

  @Test void refundBodyIsValid() throws Exception {
    assertThat(validate("devolucaoPutRequest", new DevolucaoRequest(PixAmounts.toItau(Money.brl(1000))))).isEmpty();
  }

  /** Guards the guard: a schema that accepts anything would make the tests above vacuous. */
  @Test void theSchemaRejectsABadAmount() throws Exception {
    assertThat(validate("devolucaoPutRequest", new DevolucaoRequest("10"))).isNotEmpty();
  }
}
