package com.trace.backend.controller;

import com.trace.backend.dto.response.PageResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;

final class PagedResponses {
  private PagedResponses() {}

  static <T> ResponseEntity<List<T>> of(PageResponse<T> page) {
    return ResponseEntity.ok()
        .header("X-Total-Count", Long.toString(page.total()))
        .header("X-Page-Limit", Integer.toString(page.limit()))
        .header("X-Page-Offset", Integer.toString(page.offset()))
        .header("X-Has-More", Boolean.toString(page.hasMore()))
        .body(page.items());
  }
}
