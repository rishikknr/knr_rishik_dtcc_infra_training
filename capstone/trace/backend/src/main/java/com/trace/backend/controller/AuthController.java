package com.trace.backend.controller;

import com.trace.backend.dto.request.IdentityRequests.*;
import com.trace.backend.dto.response.RegistrationOptions;
import com.trace.backend.dto.response.RegistrationResponse;
import com.trace.backend.service.IdentityService;
import com.trace.backend.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import jakarta.servlet.http.*;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final IdentityService identities;
  private final boolean secureCookies;

  public AuthController(IdentityService identities,
      @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookies) {
    this.identities = identities;
    this.secureCookies = secureCookies;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token, HttpServletRequest request) {
    if (secureCookies && !request.isSecure())
      throw new ApiException(HttpStatus.BAD_REQUEST, "HTTPS_REQUIRED",
          "Open Trace using its HTTPS address to sign in securely.");
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/registration")
  public RegistrationOptions registrationOptions(HttpServletRequest request) {
    return identities.registrationOptions(request);
  }

  @PostMapping("/register")
  @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
  public RegistrationResponse register(@RequestBody Registration body, HttpServletRequest request) {
    return identities.register(body, request);
  }

  @GetMapping("/me")
  public IdentityService.Me me(HttpServletRequest request) {
    return identities.me(request);
  }

  @PostMapping({"/organization", "/select-organization"})
  public IdentityService.Me select(@RequestBody Selection body, HttpServletRequest request) {
    return identities.select(body.organizationId(), request);
  }

  @PostMapping("/invitations/accept")
  public Map<String, Boolean> accept(
      @RequestBody Acceptance body, HttpServletRequest request, HttpServletResponse response) {
    identities.accept(body, request, response);
    return Map.of("ok", true);
  }

  @PostMapping({"/password/change", "/change-password"})
  public Map<String, Boolean> change(@RequestBody PasswordChange body, HttpServletRequest request) {
    identities.change(body, request);
    return Map.of("ok", true);
  }

  @PostMapping("/password/reset")
  public Map<String, Boolean> reset(@RequestBody Reset body, HttpServletRequest request) {
    identities.reset(body, request);
    return Map.of("ok", true);
  }

  @PatchMapping("/profile")
  public Map<String, Boolean> profile(@RequestBody Profile body) {
    identities.rename(body.displayName());
    return Map.of("ok", true);
  }
}
