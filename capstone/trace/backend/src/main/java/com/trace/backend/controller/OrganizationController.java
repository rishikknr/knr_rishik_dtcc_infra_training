package com.trace.backend.controller;

import com.trace.backend.dto.request.IdentityRequests.*;
import com.trace.backend.dto.response.IdResponse;
import com.trace.backend.dto.response.IdentityResponses;
import com.trace.backend.service.OrganizationService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OrganizationController {
  private final OrganizationService organizations;

  public OrganizationController(OrganizationService organizations) {
    this.organizations = organizations;
  }

  @GetMapping("/platform/organizations")
  public List<IdentityResponses.Organization> platform() {
    return organizations.platform();
  }

  @PostMapping("/platform/organizations")
  public IdResponse create(@RequestBody Organization body) {
    return organizations.create(body);
  }

  @PatchMapping("/platform/organizations/{id}/status")
  public Map<String, Boolean> state(@PathVariable UUID id, @RequestBody State body) {
    organizations.state(id, body.active());
    return ok();
  }

  @PatchMapping("/platform/users/{id}/status")
  public Map<String, Boolean> userState(@PathVariable UUID id, @RequestBody State body) {
    organizations.userState(id, body.active());
    return ok();
  }

  @PostMapping("/platform/users/{id}/password-recovery")
  public OrganizationService.Delivery recovery(@PathVariable UUID id) {
    return organizations.recover(id);
  }

  @GetMapping("/organization")
  public IdentityResponses.Organization current() {
    return organizations.current();
  }

  @PatchMapping("/organization")
  public Map<String, Boolean> rename(@RequestBody Organization body) {
    organizations.rename(body);
    return ok();
  }

  @GetMapping("/organization/members")
  public List<IdentityResponses.Member> members() {
    return organizations.members();
  }

  @PatchMapping("/organization/members/{user}")
  public Map<String, Boolean> member(@PathVariable UUID user, @RequestBody Membership body) {
    organizations.member(user, body);
    return ok();
  }

  @PostMapping("/organization/transfer/{user}")
  public Map<String, Boolean> transfer(@PathVariable UUID user) {
    organizations.transfer(user);
    return ok();
  }

  @GetMapping("/organization/invitations")
  public List<IdentityResponses.Invitation> invitations() {
    return organizations.invitations();
  }

  @PostMapping("/organization/invitations")
  public OrganizationService.Delivery invite(@RequestBody Invitation body) {
    return organizations.invite(body);
  }

  @DeleteMapping("/organization/invitations/{id}")
  public Map<String, Boolean> revoke(@PathVariable UUID id) {
    organizations.revoke(id);
    return ok();
  }

  @GetMapping("/organization/audit")
  public List<IdentityResponses.Audit> audit() {
    return organizations.audit();
  }

  private Map<String, Boolean> ok() {
    return Map.of("ok", true);
  }
}
