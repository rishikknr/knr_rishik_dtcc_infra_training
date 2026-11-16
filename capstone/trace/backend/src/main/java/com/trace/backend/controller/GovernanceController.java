package com.trace.backend.controller;

import com.trace.backend.service.GovernanceService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/governance/reviews")
public class GovernanceController {
  private final GovernanceService service;

  public GovernanceController(GovernanceService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String mine,
      @RequestParam(defaultValue = "25") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.list(status, mine, limit, offset);
  }

  @PostMapping
  public Map<String, Object> submit(@RequestBody GovernanceService.Submit r) {
    return service.submit(r);
  }

  @GetMapping("/{id}")
  public Map<String, Object> get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/{id}/decision")
  public Map<String, Object> decide(
      @PathVariable UUID id, @RequestBody GovernanceService.Decision d) {
    return service.decide(id, d);
  }
}
