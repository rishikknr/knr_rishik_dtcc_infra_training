package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.config.TenantConfiguration;
import com.trace.backend.security.*;
import com.trace.backend.tenancy.TenantIdentityProvider;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class TenantConfigurationTests {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void defaultFailsClosed() {
    var provider = new TenantConfiguration().tenantIdentityProvider(new MockHttpServletRequest());
    assertThrows(ResponseStatusException.class, provider::selectedIdentity);
  }

  @Test
  void identityRequiresOrganizationAndUser() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new TenantIdentityProvider.Selection(null, UUID.randomUUID()));
  }

  @Test
  void forgedHeadersAndOldDevelopmentPropertiesDoNotSelectIdentity() {
    var request = new MockHttpServletRequest();
    request.addHeader("X-User-ID", UUID.randomUUID());
    request.addHeader("X-Organization-ID", UUID.randomUUID());
    assertThrows(
        ResponseStatusException.class,
        () -> new TenantConfiguration().tenantIdentityProvider(request).selectedIdentity());
  }

  @Test
  void sessionSelectionUsesAuthenticatedPrincipalAndRequiresOrganization() {
    var request = new MockHttpServletRequest();
    var id = UUID.randomUUID();
    var p = new TracePrincipal(id, "test@example.test", "", true, 0);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(p, null, java.util.List.of()));
    var provider = new TenantConfiguration().tenantIdentityProvider(request);
    assertThrows(ResponseStatusException.class, provider::selectedIdentity);
    var org = UUID.randomUUID();
    request.getSession().setAttribute(TenantConfiguration.ORGANIZATION, org);
    assertEquals(new TenantIdentityProvider.Selection(org, id), provider.selectedIdentity());
  }

  @Test
  void passwordLimitsAccountForBcryptUtf8Limit() {
    var policy = new PasswordPolicy(12);
    assertThrows(ResponseStatusException.class, () -> policy.validate("short"));
    assertThrows(ResponseStatusException.class, () -> policy.validate("😀".repeat(30)));
    assertEquals("correct horse battery staple", policy.validate("correct horse battery staple"));
  }

  @Test
  void rateLimiterIsBoundedAndEnforcesLimit() {
    var limit = new AuthenticationRateLimiter(2, 60, 1);
    assertTrue(limit.allow("a"));
    assertTrue(limit.allow("a"));
    assertFalse(limit.allow("a"));
    assertFalse(limit.allow("b"));
  }

  @Test
  void localDeliveryCannotBeEnabledInProductionOrOnAnExternalListener() {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("server.address", "127.0.0.1");
    env.setActiveProfiles("production");
    assertThrows(
        IllegalStateException.class,
        () ->
            new com.trace.backend.service.OrganizationService(
                null, null, null, null, env, true, 48, 30));
    env.setActiveProfiles("production", "local-dev");
    assertThrows(
        IllegalStateException.class,
        () ->
            new com.trace.backend.service.OrganizationService(
                null, null, null, null, env, true, 48, 30));
    env.setActiveProfiles("local-dev");
    env.setProperty("server.address", "0.0.0.0");
    assertThrows(
        IllegalStateException.class,
        () ->
            new com.trace.backend.service.OrganizationService(
                null, null, null, null, env, true, 48, 30));
  }

  @Test
  void signupRequiresAnExclusiveLoopbackDevelopmentProfile() {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("server.address", "127.0.0.1");
    assertThrows(IllegalStateException.class, () -> new LocalSignupPolicy(env, true));
    for (String[] profiles :
        java.util.List.of(new String[] {"production"}, new String[] {"production", "local-dev"})) {
      env.setActiveProfiles(profiles);
      assertThrows(IllegalStateException.class, () -> new LocalSignupPolicy(env, true));
    }
    env.setActiveProfiles("local-dev");
    env.setProperty("server.address", "0.0.0.0");
    assertThrows(IllegalStateException.class, () -> new LocalSignupPolicy(env, true));
    env.setProperty("server.address", "127.0.0.1");
    assertNotNull(new LocalSignupPolicy(env, true));
  }

  @Test
  void signupRejectsRemoteCrossSiteAndForwardedTraffic() {
    var env =
        new org.springframework.mock.env.MockEnvironment()
            .withProperty("server.address", "127.0.0.1");
    env.setActiveProfiles("local-dev");
    var policy = new LocalSignupPolicy(env, true);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    assertTrue(policy.available(request));
    request.setRemoteAddr("203.0.113.1");
    assertFalse(policy.available(request));
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("Sec-Fetch-Site", "cross-site");
    assertFalse(policy.available(request));
    assertFalse(new LocalSignupPolicy(env, false).available(new MockHttpServletRequest()));
  }
  @Test
  void publicSignupRequiresExplicitEnablementAndHttps() {
    var env = new org.springframework.mock.env.MockEnvironment()
        .withProperty("trace.security.public-signup-enabled", "true");
    env.setActiveProfiles("staging");
    var policy = new LocalSignupPolicy(env, false);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.1");
    assertFalse(policy.available(request));
    request.setSecure(true);
    assertTrue(policy.available(request));
    request.addHeader("Sec-Fetch-Site", "cross-site");
    assertFalse(policy.available(request));
  }

  @Test
  void stagingCanExplicitlyAllowHttpSignupWithoutAllowingCrossSiteSignup() {
    var env = new org.springframework.mock.env.MockEnvironment()
        .withProperty("trace.security.public-signup-enabled", "true")
        .withProperty("trace.security.public-signup-require-https", "false");
    env.setActiveProfiles("staging");
    var policy = new LocalSignupPolicy(env, false);
    var request = new MockHttpServletRequest();
    assertTrue(policy.available(request));
    request.addHeader("Sec-Fetch-Site", "cross-site");
    assertFalse(policy.available(request));
  }

}
