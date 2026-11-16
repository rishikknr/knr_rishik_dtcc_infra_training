package com.trace.backend.controller;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.dto.response.*;
import com.trace.backend.service.ManualMetadataService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ManualMetadataController {
  private final com.trace.backend.service.BrowseService browse;
  private final ManualMetadataService service;

  public ManualMetadataController(
      ManualMetadataService service, com.trace.backend.service.BrowseService browse) {
    this.browse = browse;
    this.service = service;
  }

  @PostMapping("/assets")
  public IdResponse create(@RequestBody Asset r) {
    return service.create(r);
  }

  @GetMapping("/assets/{id}/curation")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PatchMapping("/assets/{id}")
  public Map<String, Object> edit(@PathVariable UUID id, @RequestBody Asset r) {
    return service.update(id, r);
  }

  @PostMapping("/assets/{id}/columns")
  public IdResponse column(@PathVariable UUID id, @RequestBody Column r) {
    return service.column(id, null, r);
  }

  @PatchMapping("/assets/{id}/columns/{column}")
  public IdResponse column(
      @PathVariable UUID id, @PathVariable UUID column, @RequestBody Column r) {
    return service.column(id, column, r);
  }

  public record NewTag(String name) {}

  @PostMapping("/tags")
  public IdResponse tag(@RequestBody NewTag r) {
    return service.tag(r.name());
  }

  @GetMapping("/imports/formats")
  public Set<String> formats() {
    return Set.of(
        "SNOWFLAKE_CSV",
        "GENERIC_CSV",
        "S3_INVENTORY",
        "METADATA_JSON",
        "SQL",
        "SQL_ZIP",
        "POWERBI_CSV",
        "POWERBI_MAPPING_CSV");
  }

  @GetMapping("/imports")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> history(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("imports", limit, offset, q, null, "", ""));
  }

  @GetMapping("/import-mappings")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> profiles(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("profiles", limit, offset, q, null, "", ""));
  }

  @PostMapping("/import-mappings")
  public IdResponse profile(@RequestBody MappingProfile r) {
    return service.profile(r);
  }
}
