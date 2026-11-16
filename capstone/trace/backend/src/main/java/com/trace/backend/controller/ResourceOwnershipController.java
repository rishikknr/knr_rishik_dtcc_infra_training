package com.trace.backend.controller;

import com.trace.backend.service.ResourceOwnershipService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ResourceOwnershipController {
  private final ResourceOwnershipService resources;

  public ResourceOwnershipController(ResourceOwnershipService resources) {
    this.resources = resources;
  }

  public record Transfer(UUID ownerTeamId) {}

  @PatchMapping("/connections/{id}/owner-team")
  public Map<String, Boolean> connection(@PathVariable UUID id, @RequestBody Transfer body) {
    resources.transfer(id, body.ownerTeamId(), true);
    return Map.of("ok", true);
  }

  @PatchMapping("/assets/{id}/owner-team")
  public Map<String, Boolean> asset(@PathVariable UUID id, @RequestBody Transfer body) {
    resources.transfer(id, body.ownerTeamId(), false);
    return Map.of("ok", true);
  }
}
