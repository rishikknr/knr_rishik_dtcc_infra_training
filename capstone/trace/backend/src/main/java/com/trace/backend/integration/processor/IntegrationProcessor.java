package com.trace.backend.integration.processor;

import com.trace.backend.dto.response.MetadataPreview;
import com.trace.backend.exception.ApiException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import tools.jackson.databind.*;

@Component
public class IntegrationProcessor {
  private final java.net.http.HttpClient client;
  private final java.net.URI base;
  private final String token;
  private final ObjectMapper json;

  public IntegrationProcessor(
      @Value("${trace.processor.url}") String url,
      @Value("${trace.processor.token:}") String token,
      ObjectMapper json) {
    this.base = java.net.URI.create(url.replaceAll("/$", "") + "/internal/");
    this.token = token;
    this.json = json;
    client =
        java.net.http.HttpClient.newBuilder()
            .version(java.net.http.HttpClient.Version.HTTP_1_1)
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
  }

  public JsonNode call(String path, Object body) {
    if (token.isBlank())
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PROCESSOR_AUTH_UNAVAILABLE",
          "Private processor authentication is not configured");
    try {
      var req =
          java.net.http.HttpRequest.newBuilder(base.resolve(path))
              .timeout(java.time.Duration.ofSeconds(120))
              .header("X-Trace-Processor-Token", token)
              .header("Content-Type", "application/json")
              .POST(
                  java.net.http.HttpRequest.BodyPublishers.ofByteArray(
                      json.writeValueAsBytes(body)))
              .build();
      var response = client.send(req, info -> new BoundedResponse(8 * 1024 * 1024));
      if (response.statusCode() == 422)
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVALID_METADATA",
            "Metadata validation failed. Check the selected format, field mapping, identifiers and"
                + " file limits.");
      if (response.statusCode() != 200)
        throw new ApiException(
            HttpStatus.BAD_GATEWAY,
            "PROCESSOR_ERROR",
            "Private processor could not complete this operation");
      if (response.body().length > 8 * 1024 * 1024) throw new IllegalArgumentException();
      return json.readTree(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CANCELLED",
          "Discovery interrupted; remote work may require its bounded timeout to finish");
    } catch (java.io.IOException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "PROCESSOR_UNAVAILABLE",
          "Metadata processor is unavailable or timed out");
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PROCESSOR_INVALID_RESPONSE",
          "Metadata processor returned invalid metadata");
    }
  }

  public MetadataPreview batch(JsonNode node) {
    try {
      if (node.path("schemaVersion").asInt(-1) != 1) throw new IllegalArgumentException();
      return json.treeToValue(node, MetadataPreview.class).validated();
    } catch (RuntimeException e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "PROCESSOR_INVALID_RESPONSE",
          "Metadata processor returned an incompatible or invalid metadata snapshot");
    }
  }

  public MetadataPreview parse(
      byte[] content, String format, Map<String, String> mapping, String dialect, String filename) {
    return batch(
        call(
            "parse",
            Map.of(
                "content",
                Base64.getEncoder().encodeToString(content),
                "format",
                format,
                "mapping",
                mapping,
                "dialect",
                dialect,
                "filename",
                filename)));
  }
}
