package com.trace.backend.controller;

import com.trace.backend.dto.request.ConnectionSettings;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.service.ConnectionService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {
  private final com.trace.backend.service.BrowseService browse;
  private final ConnectionService service;

  public ConnectionController(
      ConnectionService service, com.trace.backend.service.BrowseService browse) {
    this.browse = browse;
    this.service = service;
  }

  @GetMapping
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> list(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("connections", limit, offset, q, null, "", ""));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public IdResponse create(@RequestBody ConnectionSettings r) {
    return service.create(r);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PatchMapping("/{id}")
  public Map<String, Object> update(@PathVariable UUID id, @RequestBody ConnectionSettings r) {
    return service.update(id, r);
  }

  public record Status(boolean enabled) {}

  @PatchMapping("/{id}/status")
  public Map<String, Object> status(@PathVariable UUID id, @RequestBody Status r) {
    return service.enabled(id, r.enabled());
  }

  @PutMapping("/{id}/credentials")
  public Map<String, Boolean> credentials(
      @PathVariable UUID id, @RequestBody Map<String, String> r) {
    return service.credentials(id, r);
  }

  @PostMapping("/{id}/test")
  public Map<String, Object> test(@PathVariable UUID id) {
    return service.test(id);
  }

  @PostMapping("/{id}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> sync(@PathVariable UUID id) {
    return service.sync(id);
  }

  @GetMapping("/{id}/sync-runs")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> runs(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    service.get(id);
    return PagedResponses.of(browse.page("sync-runs", limit, offset, q, id, "", ""));
  }

  @GetMapping("/{id}/sync-runs/{run}")
  public Map<String, Object> run(@PathVariable UUID id, @PathVariable UUID run) {
    return service.run(id, run);
  }

  @PostMapping("/{id}/sync-runs/{run}/cancel")
  public Map<String, Object> cancel(@PathVariable UUID id, @PathVariable UUID run) {
    return service.cancel(id, run);
  }

  @PostMapping("/{id}/sync-runs/{run}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> retry(@PathVariable UUID id, @PathVariable UUID run) {
    return service.retry(id, run);
  }

  @GetMapping("/{id}/capabilities")
  public Map<String, Object> capabilities(@PathVariable UUID id) {
    return service.capabilities(id);
  }
}
