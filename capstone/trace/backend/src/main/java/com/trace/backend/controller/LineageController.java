package com.trace.backend.controller;

import com.trace.backend.dto.request.MetadataRequests.Mapping;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.service.LineageService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/lineage")
public class LineageController {
  private final LineageService service;
  private final com.trace.backend.repository.GraphExplorerRepository explorer;
  private final com.trace.backend.tenancy.TenantTransactions tenants;

  public LineageController(
      LineageService service,
      com.trace.backend.repository.GraphExplorerRepository explorer,
      com.trace.backend.tenancy.TenantTransactions tenants) {
    this.explorer = explorer;
    this.tenants = tenants;
    this.service = service;
  }

  @PostMapping("/mappings")
  public IdResponse create(@RequestBody Mapping r) {
    return service.create(r);
  }

  @GetMapping("/mappings/{id}")
  public Map<String, Object> mapping(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/mappings/{id}/revoke")
  public Map<String, Boolean> revoke(@PathVariable UUID id) {
    return service.revoke(id);
  }

  @GetMapping("/{id}")
  public Map<String, Object> graph(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int depth,
      @RequestParam(defaultValue = "both") String direction,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) UUID columnId) {
    return explore(id, direction, offset, limit, snapshot, columnId);
  }

  @GetMapping("/{id}/downstream")
  public Map<String, Object> downstream(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int depth,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) UUID columnId) {
    return explore(id, "downstream", offset, limit, snapshot, columnId);
  }

  @GetMapping("/{id}/upstream")
  public Map<String, Object> upstream(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int depth,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(required = false) String snapshot,
      @RequestParam(required = false) UUID columnId) {
    return explore(id, "upstream", offset, limit, snapshot, columnId);
  }

  @GetMapping("/{id}/path")
  public Map<String, Object> path(
      @PathVariable UUID id,
      @RequestParam UUID targetId,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "200") int limit,
      @RequestParam(required = false) UUID columnId) {
    if (offset < 0 || limit < 1 || limit > 500)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid path page");
    return tenants.executeSnapshot(t -> explorer.path(t, id, targetId, offset, limit, columnId));
  }

  @GetMapping("/reference-columns")
  public List<Map<String, Object>> referenceColumns(@RequestParam List<UUID> ids) {
    if (ids.size() > 100)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "At most 100 column references may be inspected");
    return tenants.executeSnapshot(t -> explorer.referenceColumns(t, ids));
  }

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    return tenants.executeSnapshot(explorer::overview);
  }

  private Map<String, Object> explore(
      UUID id, String direction, int offset, int limit, String snapshot, UUID column) {
    if (!Set.of("both", "upstream", "downstream").contains(direction)
        || offset < 0
        || limit < 1
        || limit > 200)
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid traversal parameters");
    return tenants.executeSnapshot(
        t -> explorer.explore(t, id, direction, offset, limit, snapshot, column));
  }
}
