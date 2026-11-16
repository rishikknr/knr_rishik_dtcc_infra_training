package com.trace.backend.controller;

import com.trace.backend.dto.response.*;
import com.trace.backend.service.RegistryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RegistryController {
  private final RegistryService service;

  public RegistryController(RegistryService service) {
    this.service = service;
  }

  @GetMapping("/registry/status")
  public RegistryStatusResponse status() {
    return service.status();
  }
}
