package com.trace.backend.controller;

import com.trace.backend.dto.response.*;
import com.trace.backend.service.ImportService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
  private final ImportService imports;
  private final com.trace.backend.service.ManualMetadataService manual;

  public ImportController(
      ImportService imports, com.trace.backend.service.ManualMetadataService manual) {
    this.manual = manual;
    this.imports = imports;
  }

  @PostMapping(
      value = {"", "/preview"},
      consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public ImportResponse preview(
      @RequestParam UUID connectionId,
      @RequestParam MultipartFile file,
      @RequestParam(defaultValue = "SNOWFLAKE_CSV") String format,
      @RequestParam(defaultValue = "{}") String mapping,
      @RequestParam(defaultValue = "snowflake") String dialect)
      throws IOException {
    if ("SNOWFLAKE_CSV".equals(format))
      return imports.get(
          imports.preview(connectionId, file.getBytes(), file.getOriginalFilename()));
    java.util.Map<String, String> fields;
    try {
      fields =
          new tools.jackson.databind.ObjectMapper()
              .readValue(
                  mapping,
                  new tools.jackson.core.type.TypeReference<java.util.Map<String, String>>() {});
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid field mapping");
    }
    return manual.preview(
        connectionId, file.getBytes(), file.getOriginalFilename(), format, fields, dialect);
  }

  @GetMapping("/{id}")
  public ImportResponse get(@PathVariable UUID id) {
    return imports.get(id);
  }

  @PostMapping("/{id}/commit")
  public ImportResponse commit(@PathVariable UUID id) {
    return imports.commit(id);
  }
}
