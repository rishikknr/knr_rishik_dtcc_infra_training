package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.processor.IntegrationProcessor;
import java.net.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IntegrationProcessorBoundaryTests {
  @Test
  void oversizedStreamingPayloadIsCancelledBeforeParsingAndNeverRelayed() throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/internal/acquire",
        e -> {
          e.getRequestBody().readAllBytes();
          e.sendResponseHeaders(200, 0);
          byte[] chunk = new byte[65536];
          java.util.Arrays.fill(chunk, (byte) 'x');
          try {
            for (int n = 0; n < 140; n++) e.getResponseBody().write(chunk);
          } catch (java.io.IOException ignored) {
          } finally {
            e.close();
          }
        });
    server.start();
    try {
      var client =
          new IntegrationProcessor(
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-token",
              new ObjectMapper());
      var error =
          assertThrows(ApiException.class, () -> client.call("acquire", java.util.Map.of()));
      assertEquals("PROCESSOR_UNAVAILABLE", error.code);
      assertFalse(error.getMessage().contains("xxxx"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void aCompleteSnapshotWithoutAScopeIsRejected() {
    var p =
        new com.trace.backend.dto.response.MetadataPreview(
            java.util.List.of(), java.util.List.of(), 1, java.util.List.of(), true, null);
    assertThrows(IllegalArgumentException.class, p::validated);
  }
}
