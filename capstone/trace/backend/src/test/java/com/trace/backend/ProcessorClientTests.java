package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.processor.ProcessorClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProcessorClientTests {
  private ApiException failure(int status, String payload) throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/parse/snowflake-columns",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          var body = payload.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      return assertThrows(
          ApiException.class,
          () ->
              new ProcessorClient("http://127.0.0.1:" + server.getAddress().getPort())
                  .parse(new byte[] {1}));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void unavailableProcessorIs503() throws Exception {
    int port;
    try (var socket = new java.net.ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    var e =
        assertThrows(
            ApiException.class,
            () -> new ProcessorClient("http://127.0.0.1:" + port).parse(new byte[] {1}));
    assertEquals("PROCESSOR_UNAVAILABLE", e.code);
    assertEquals(503, e.status.value());
  }

  @Test
  void invalidJsonAndEmptyOrDuplicateMetadataAre502() throws Exception {
    for (var body :
        java.util.List.of(
            "{invalid",
            "null",
            "{\"datasets\":[]}",
            "{\"datasets\":[{\"externalKey\":\"DB.RAW.X\",\"name\":\"RAW.X\",\"columns\":[]}]}",
            "{\"datasets\":[{\"externalKey\":\"DB.RAW.X\",\"name\":\"RAW.X\",\"columns\":[{\"name\":\"ID\",\"dataType\":\"NUMBER\"},{\"name\":\"ID\",\"dataType\":\"NUMBER\"}]}]}")) {
      var e = failure(200, body);
      assertEquals("PROCESSOR_INVALID_RESPONSE", e.code);
      assertEquals(502, e.status.value());
    }
  }

  @Test
  void validationErrorsAreSafeAndRowSpecific() throws Exception {
    var e =
        failure(
            422,
            "{\"detail\":{\"errors\":[{\"row\":3,\"code\":\"EMPTY_VALUE\",\"field\":\"data_type\",\"message\":\"private-source-data\"}]}}");
    assertEquals(422, e.status.value());
    assertEquals(3, e.details.getFirst().row());
    assertEquals("data_type", e.details.getFirst().field());
    assertFalse(e.details.getFirst().message().contains("private-source"));
    assertEquals(413, failure(413, "{}").status.value());
  }

  @Test
  void upstreamErrorsDoNotDisclosePayloads() throws Exception {
    var e = failure(500, "private-database-password");
    assertEquals(502, e.status.value());
    assertFalse(e.getMessage().contains("password"));
  }
}
