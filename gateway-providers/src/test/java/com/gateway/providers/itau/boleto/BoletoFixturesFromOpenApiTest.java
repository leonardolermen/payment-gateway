package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every replayed fixture is the bank's own example; a hand-"improved" fixture drifts from the bank and fails here. */
class BoletoFixturesFromOpenApiTest {
  static final ObjectMapper M = new ObjectMapper();

  static JsonNode example(String api, String pointer) throws Exception {
    JsonNode doc = M.readTree(Files.readString(Path.of("src/test/resources/itau/boleto/" + api + ".openapi.json")));
    JsonNode ex = doc.at(pointer);
    assertThat(ex.isMissingNode()).as("%s exists in %s", pointer, api).isFalse();
    return ex;
  }

  static JsonNode fixture(String file) throws Exception {
    return M.readTree(Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + file)));
  }

  @Test void issueRequestMin() throws Exception { assertThat(fixture("post_boletos_pix_request_min.json")).isEqualTo(example("issue", "/components/examples/requestPostBoletosPix/value")); }
  @Test void issue200() throws Exception { assertThat(fixture("post_boletos_pix_200.json")).isEqualTo(example("issue", "/components/examples/200boletoPixResponse/value")); }

  @Test void query200() throws Exception { assertThat(fixture("get_boletos_200.json")).isEqualTo(example("query", "/components/examples/query_200_boletos_get_response/value")); }
  @Test void query404() throws Exception { assertThat(fixture("get_boletos_404.json")).isEqualTo(example("query", "/components/responses/404/content/application~1json/examples/404/value")); }

  @Test void baixa200() throws Exception { assertThat(fixture("patch_baixa_200.json")).isEqualTo(example("instruction", "/components/examples/200_instrucao/value/value")); }
}
