package com.trace.backend.controller;

import com.trace.backend.dto.response.PageResponse;
import com.trace.backend.service.BrowseService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/browse")
public class BrowseController {
  private final BrowseService service;

  public BrowseController(BrowseService service) {
    this.service = service;
  }

  @GetMapping("/{kind}")
  public PageResponse<Map<String, Object>> page(
      @PathVariable String kind,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) UUID parent,
      @RequestParam(defaultValue = "") String type,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String sort,
      @RequestParam(required = false) UUID source,
      @RequestParam Map<String, String> filters) {
    return service.page(kind, limit, offset, q, parent, type, status, sort, source, filters);
  }
}
