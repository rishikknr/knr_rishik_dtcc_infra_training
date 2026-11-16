package com.trace.backend.controller;

import com.trace.backend.service.MappingSuggestionService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/mapping-suggestions")
public class MappingSuggestionController {
  private final MappingSuggestionService service;

  public MappingSuggestionController(MappingSuggestionService service) {
    this.service = service;
  }

  @GetMapping
  public Map<String, Object> list(@RequestParam(required = false) UUID source) {
    return service.list(source);
  }

  @PostMapping("/confirm")
  public Map<String, Object> confirm(@RequestBody MappingSuggestionService.Confirm r) {
    return service.confirm(r);
  }
}
