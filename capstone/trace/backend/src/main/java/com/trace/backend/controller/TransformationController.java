package com.trace.backend.controller;

import com.trace.backend.service.TransformationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transformations")
public class TransformationController {
  private final TransformationService service;

  public TransformationController(TransformationService service) {
    this.service = service;
  }

  @PostMapping("/{id}/analyze")
  public Map<String, Object> analyze(
      @PathVariable UUID id, @RequestBody TransformationService.Analyze r) {
    return service.analyze(id, r);
  }

  @PostMapping("/{id}/confirm")
  public Map<String, Object> confirm(
      @PathVariable UUID id, @RequestBody TransformationService.Confirm r) {
    return service.confirm(id, r);
  }

  @GetMapping("/{id}/history")
  public List<Map<String, Object>> history(@PathVariable UUID id) {
    return service.history(id);
  }

  @GetMapping("/{id}/history/page")
  public Map<String, Object> historyPage(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.historyPage(id, limit, offset);
  }
}
