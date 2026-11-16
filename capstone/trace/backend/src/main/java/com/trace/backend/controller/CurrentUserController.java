package com.trace.backend.controller;

import com.trace.backend.dto.response.IdentityResponses.*;
import com.trace.backend.service.IdentityService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class CurrentUserController {
  private final IdentityService identities;

  public CurrentUserController(IdentityService identities) {
    this.identities = identities;
  }

  @GetMapping("/organizations")
  public List<AvailableOrganization> organizations() {
    return identities.organizations();
  }

  @GetMapping("/permissions")
  public Permissions permissions() {
    return identities.permissions();
  }
}
