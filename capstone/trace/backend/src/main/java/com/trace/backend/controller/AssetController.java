package com.trace.backend.controller;

import com.trace.backend.dto.response.*;
import com.trace.backend.service.AssetService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
public class AssetController {
  private final AssetService service;
  private final com.trace.backend.service.BrowseService browse;

  public AssetController(AssetService service, com.trace.backend.service.BrowseService browse) {
    this.browse = browse;
    this.service = service;
  }

  @GetMapping
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return PagedResponses.of(browse.page("assets", limit, offset, q, null, "", ""));
  }

  @GetMapping("/{id}")
  public AssetResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @GetMapping("/{id}/columns")
  public List<ColumnResponse> columns(@PathVariable UUID id) {
    return service.columns(id);
  }
}
