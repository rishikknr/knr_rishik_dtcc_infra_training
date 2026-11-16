package com.trace.backend.controller;

import com.trace.backend.dto.request.MetadataRequests.*;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FormulaMetricController {
  private final com.trace.backend.service.BrowseService browse;
  private final FormulaService formulas;
  private final MetricService metrics;

  public FormulaMetricController(
      FormulaService formulas,
      MetricService metrics,
      com.trace.backend.service.BrowseService browse) {
    this.browse = browse;
    this.formulas = formulas;
    this.metrics = metrics;
  }

  @GetMapping("/formulas")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> formulas(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("formulas", limit, offset, q, null, "", ""));
  }

  @PostMapping("/formulas")
  public IdResponse formula(@RequestBody Formula r) {
    return formulas.create(r);
  }

  @GetMapping("/formulas/{id}")
  public Map<String, Object> formula(@PathVariable UUID id) {
    return formulas.get(id);
  }

  @PostMapping("/formulas/{id}/versions")
  public IdResponse version(@PathVariable UUID id, @RequestBody FormulaVersion r) {
    return formulas.version(id, r);
  }

  @GetMapping("/formulas/{id}/versions/{version}")
  public Map<String, Object> snapshot(@PathVariable UUID id, @PathVariable UUID version) {
    return formulas.snapshot(id, version);
  }

  @PatchMapping("/formulas/{id}/versions/{version}")
  public Map<String, Object> draft(
      @PathVariable UUID id, @PathVariable UUID version, @RequestBody DraftEdit r) {
    return formulas.editDraft(id, version, r);
  }

  @PostMapping("/formulas/validate")
  public Object validate(@RequestBody Validation r) {
    return formulas.validate(r);
  }

  @GetMapping("/metrics")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> metrics(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("metrics", limit, offset, q, null, "", ""));
  }

  @PostMapping("/metrics")
  public IdResponse metric(@RequestBody Metric r) {
    return metrics.create(r);
  }

  @GetMapping("/metrics/{id}")
  public Map<String, Object> metric(@PathVariable UUID id) {
    return metrics.get(id);
  }

  @PatchMapping("/metrics/{id}")
  public Map<String, Object> editMetric(@PathVariable UUID id, @RequestBody MetricEdit r) {
    return metrics.update(id, r);
  }

  @PostMapping("/metric-mappings/{id}/revoke")
  public Map<String, Boolean> revokeUsage(@PathVariable UUID id) {
    return metrics.revoke(id);
  }

  @GetMapping("/metrics/{id}/versions")
  public Object versions(@PathVariable UUID id) {
    return metrics.get(id).get("versions");
  }

  @PostMapping("/metrics/{id}/versions")
  public IdResponse metricVersion(@PathVariable UUID id, @RequestBody MetricVersion r) {
    return metrics.version(id, r);
  }

  @GetMapping("/metric-mappings")
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> mappings(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("metric-mappings", limit, offset, q, null, "", ""));
  }

  @PostMapping("/metric-mappings")
  public IdResponse mapping(@RequestBody MetricMapping r) {
    return metrics.mapping(r);
  }
}
