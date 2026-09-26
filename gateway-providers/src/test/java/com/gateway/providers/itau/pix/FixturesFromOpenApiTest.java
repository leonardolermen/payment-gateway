package com.gateway.providers.itau.pix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every fixture we replay in WireMock must be an example from Itaú's own OpenAPI. This test pins
 * that: if someone "improves" a fixture by hand, it drifts from the bank and this fails. The
 * fixtures with no OpenAPI example are listed, with their source, in fixtures/README.md.
 */
class FixturesFromOpenApiTest {
  static final ObjectMapper M = new ObjectMapper();

  static JsonNode example(String name) throws Exception {
    JsonNode api = M.readTree(Files.readString(Path.of("src/test/resources/itau/openapi.json")));
    JsonNode ex = api.at("/components/examples/" + name + "/value");
    assertThat(ex.isMissingNode()).as("example %s exists in the OpenAPI", name).isFalse();
    return ex;
  }

  static JsonNode fixture(String file) throws Exception {
    return M.readTree(Files.readString(Path.of("src/test/resources/itau/fixtures/" + file)));
  }

  @Test
  void putCobRequestMin() throws Exception {
    assertThat(fixture("put_cob_request_min.json"))
        .isEqualTo(example("request_put_cobranca_imediata_campos_obrigatorios"));
  }

  @Test
  void putCob201() throws Exception {
    assertThat(fixture("put_cob_201.json"))
        .isEqualTo(example("response_200_cobranca_imediata_txid"));
  }

  // The OpenAPI nests this example's payload under value.value.
  @Test
  void getCob200Active() throws Exception {
    assertThat(fixture("get_cob_200_active.json"))
        .isEqualTo(example("200_cobranca_txid").path("value"));
  }

  @Test
  void patchCancel() throws Exception {
    assertThat(fixture("patch_cob_cancel_request.json"))
        .isEqualTo(example("request_patch_cobranca_imediata_status"));
  }

  @Test
  void putDevolucaoRequest() throws Exception {
    assertThat(fixture("put_devolucao_request.json"))
        .isEqualTo(example("request_put_devolucao_campos_obrigatorios"));
  }

  @Test
  void getDevolucao200() throws Exception {
    assertThat(fixture("get_devolucao_200_done.json")).isEqualTo(example("200_devolucao"));
  }

  @Test
  void getCobList200() throws Exception {
    assertThat(fixture("get_cob_list_200.json")).isEqualTo(example("200_cobrancas").path("value"));
  }
}
