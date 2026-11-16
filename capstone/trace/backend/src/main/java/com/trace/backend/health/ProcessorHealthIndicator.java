package com.trace.backend.health;

import com.trace.backend.integration.processor.BoundedResponse;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.*;
import org.springframework.stereotype.Component;

@Component("parser")
public class ProcessorHealthIndicator implements HealthIndicator {
  private final URI url;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

  public ProcessorHealthIndicator(@Value("${trace.processor.url}") String url) {
    this.url = URI.create(url.replaceAll("/$", "") + "/health");
  }

  @Override
  public Health health() {
    try {
      var response =
          client.send(
              HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(2)).GET().build(),
              info -> new BoundedResponse(1024));
      if (response.statusCode() == 200
          && new tools.jackson.databind.ObjectMapper()
              .readTree(response.body())
              .path("status")
              .asString()
              .equals("ok")) return Health.up().build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      /* No provider payloads or endpoint secrets in health responses. */
    }
    return Health.down().build();
  }
}
