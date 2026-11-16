package com.trace.backend.integration.processor;

import com.trace.backend.dto.response.MetadataPreview;
import com.trace.backend.exception.ApiException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProcessorClient implements MetadataParser {
  private final java.net.http.HttpClient client;
  private final java.net.URI url;

  public ProcessorClient(@Value("${trace.processor.url:http://127.0.0.1:8000}") String url) {
    this.url = java.net.URI.create(url.replaceAll("/$", "") + "/parse/snowflake-columns");
    client =
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public MetadataPreview parse(byte[] csv) {
    try {
      var response =
          client.send(
              java.net.http.HttpRequest.newBuilder(url)
                  .timeout(java.time.Duration.ofSeconds(30))
                  .header("Content-Type", "text/csv")
                  .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(csv))
                  .build(),
              info -> new BoundedResponse(8 * 1024 * 1024));
      if (response.statusCode() != 200)
        throw new RestClientResponseException(
            "Processor failure",
            response.statusCode(),
            "",
            null,
            response.body(),
            java.nio.charset.StandardCharsets.UTF_8);
      var result = new ObjectMapper().readValue(response.body(), MetadataPreview.class);
      if (result == null) throw new IllegalArgumentException();
      return result.validated();
    } catch (RestClientResponseException e) {
      if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 413) {
        var details = new ArrayList<ApiException.Detail>();
        try {
          var tree = new ObjectMapper().readTree(e.getResponseBodyAsString()).get("detail");
          var errors = tree.get("errors");
          if (errors != null && errors.isArray())
            for (var item : errors) {
              if (details.size() >= 50) break;
              // Only allow known validation messages from the processor; never relay raw upstream
              // bodies.
              String code = item.path("code").asString();
              String message =
                  switch (code) {
                    case "MISSING_HEADERS" ->
                        "Required headers: database, schema, table, column, data_type";
                    case "EMPTY_VALUE" -> "Required value is empty";
                    case "IDENTIFIER" -> "Invalid or unsupported Snowflake identifier";
                    case "CONFLICT" -> "Conflicting definitions for the same column";
                    case "LIMIT" -> "CSV exceeds supported size or row limits";
                    case "DESCRIPTION" -> "Description must be at most 4000 characters";
                    default -> "Malformed or invalid CSV metadata";
                  };
              int row = item.path("row").asInt(0);
              String field = item.path("field").asString("");
              if (!Set.of("database", "schema", "table", "column", "data_type", "description")
                  .contains(field)) field = "csv";
              details.add(new ApiException.Detail(row > 0 ? row : null, field, message));
            }
        } catch (RuntimeException ignored) {
        }
        throw new ApiException(
            e.getStatusCode().value() == 413
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.UNPROCESSABLE_ENTITY,
            "INVALID_CSV",
            "Snowflake metadata CSV validation failed",
            details);
      }
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PROCESSOR_ERROR",
          "Metadata processor could not complete the request");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PROCESSOR_UNAVAILABLE",
          "Metadata processor request was interrupted. Try again later.");
    } catch (java.io.IOException | ResourceAccessException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PROCESSOR_UNAVAILABLE",
          "Metadata processor is unavailable. Try again later.");
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PROCESSOR_INVALID_RESPONSE",
          "Metadata processor returned an invalid response");
    }
  }
}
