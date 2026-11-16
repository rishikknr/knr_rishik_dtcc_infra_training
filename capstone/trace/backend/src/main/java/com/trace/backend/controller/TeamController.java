package com.trace.backend.controller;

import com.trace.backend.dto.request.IdentityRequests.*;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.dto.response.IdentityResponses;
import com.trace.backend.service.OrganizationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
  private final OrganizationService organizations;
  private final com.trace.backend.service.BrowseService browse;

  public TeamController(
      OrganizationService organizations, com.trace.backend.service.BrowseService browse) {
    this.browse = browse;
    this.organizations = organizations;
  }

  @GetMapping
  public org.springframework.http.ResponseEntity<List<Map<String, Object>>> teams(
      @RequestParam(defaultValue = "100") int limit,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String q) {
    return PagedResponses.of(browse.page("teams", limit, offset, q, null, "", ""));
  }

  @PostMapping
  public IdResponse create(@RequestBody Team body) {
    return organizations.saveTeam(null, body);
  }

  @GetMapping("/{id}")
  public IdentityResponses.TeamDetail team(@PathVariable UUID id) {
    return organizations.team(id);
  }

  @PatchMapping("/{id}")
  public IdResponse update(@PathVariable UUID id, @RequestBody Team body) {
    return organizations.saveTeam(id, body);
  }

  @GetMapping("/{id}/candidates")
  public List<IdentityResponses.Member> candidates(@PathVariable UUID id) {
    return organizations.candidates(id);
  }

  @PostMapping("/{id}/members")
  public Map<String, Boolean> member(@PathVariable UUID id, @RequestBody TeamMember body) {
    organizations.teamMember(id, body.userId(), body.role());
    return Map.of("ok", true);
  }

  @PatchMapping("/{id}/members/{user}")
  public Map<String, Boolean> updateMember(
      @PathVariable UUID id, @PathVariable UUID user, @RequestBody TeamMember body) {
    organizations.teamMember(id, user, body.role());
    return Map.of("ok", true);
  }

  @DeleteMapping("/{id}/members/{user}")
  public Map<String, Boolean> remove(@PathVariable UUID id, @PathVariable UUID user) {
    organizations.teamMember(id, user, null);
    return Map.of("ok", true);
  }
}
