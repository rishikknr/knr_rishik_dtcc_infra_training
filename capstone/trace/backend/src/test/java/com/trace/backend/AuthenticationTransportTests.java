package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.trace.backend.config.SecurityConfiguration;
import com.trace.backend.entity.Identity;
import com.trace.backend.repository.IdentityRepository;
import com.trace.backend.security.AuthenticationRateLimiter;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Real security filter chain with an in-memory identity fixture; runs in ordinary CI. */
class AuthenticationTransportTests {
  static final UUID USER = UUID.randomUUID();
  AnnotationConfigWebApplicationContext context;
  MockMvc mvc;

  @BeforeEach
  void start() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(Fixture.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @AfterEach
  void stop() { context.close(); }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import(SecurityConfiguration.class)
  static class Fixture {
    @Bean AuthenticationRateLimiter limiter() { return new AuthenticationRateLimiter(200, 60, 1000); }
    @Bean Endpoints endpoints() { return new Endpoints(); }
    @Bean IdentityRepository identities() {
      return new IdentityRepository(null) {
        final String hash = new BCryptPasswordEncoder(10).encode("Transport test password!");
        public Optional<Credentials> credentials(String email) {
          return "user@example.test".equals(email)
              ? Optional.of(new Credentials(USER, email, hash, true, 0)) : Optional.empty();
        }
        public Identity profile(UUID id) { return new Identity(USER, "user@example.test", "Test", true, null, 0); }
        public void login(UUID id) {}
        public void audit(UUID actor, UUID org, String event, String kind, UUID resource, String outcome) {}
      };
    }
  }

  @RestController
  static class Endpoints {
    @GetMapping("/api/auth/csrf")
    Map<String, String> token(CsrfToken token) {
      return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/api/private") Map<String, Boolean> read() { return Map.of("ok", true); }
    @PostMapping("/api/private") Map<String, Boolean> write() { return Map.of("ok", true); }
  }

  record Token(MockHttpSession session, String value) {}
  Token token(MockHttpSession session) throws Exception {
    var request = get("/api/auth/csrf");
    if (session != null) request.session(session);
    var result = mvc.perform(request).andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andReturn();
    var body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
    assertEquals("X-CSRF-TOKEN", body.get("headerName").asText());
    return new Token((MockHttpSession) result.getRequest().getSession(), body.get("token").asText());
  }

  ResultActions login(Token token, String password) throws Exception {
    return mvc.perform(post("/api/auth/login").session(token.session())
        .secure(true).header("Origin", "https://trace.example.test")
        .header("X-CSRF-TOKEN", token.value())
        .param("email", "user@example.test").param("password", password));
  }

  @Test
  void validLoginRotationAuthenticatedWriteAndLogoutLogin() throws Exception {
    var before = token(null);
    login(before, "Transport test password!").andExpect(status().isOk());
    mvc.perform(get("/api/private").session(before.session())).andExpect(status().isOk());
    // Authentication clears the old CSRF token, while preserving the session identity.
    mvc.perform(post("/api/private").session(before.session()).header("X-CSRF-TOKEN", before.value()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var renewed = token(before.session());
    mvc.perform(post("/api/private").session(renewed.session()).header("X-CSRF-TOKEN", renewed.value()))
        .andExpect(status().isOk());
    mvc.perform(post("/api/auth/logout").session(renewed.session()).header("X-CSRF-TOKEN", renewed.value()))
        .andExpect(status().isOk());
    assertTrue(renewed.session().isInvalid());
    mvc.perform(get("/api/private")).andExpect(status().isUnauthorized());
    login(token(null), "Transport test password!").andExpect(status().isOk());
  }

  @Test
  void invalidPasswordAndUnknownEmailReturnSameSafeResponse() throws Exception {
    var token = token(null);
    var invalid = login(token, "incorrect").andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn();
    var unknown = mvc.perform(post("/api/auth/login").session(token.session())
        .header("X-CSRF-TOKEN", token.value()).param("email", "unknown@example.test").param("password", "incorrect"))
        .andExpect(status().isUnauthorized()).andReturn();
    assertEquals(invalid.getResponse().getContentAsString(), unknown.getResponse().getContentAsString());
  }

  @Test
  void crossSiteMissingInvalidAndForeignSessionTokensCannotLogIn() throws Exception {
    mvc.perform(post("/api/auth/login").header("Origin", "https://attacker.example")
        .header("Sec-Fetch-Site", "cross-site").param("email", "user@example.test")
        .param("password", "Transport test password!"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var first = token(null);
    var other = token(null);
    mvc.perform(post("/api/auth/login").session(other.session()).header("X-CSRF-TOKEN", first.value()))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/auth/login").session(first.session()).header("X-CSRF-TOKEN", "invalid"))
        .andExpect(status().isForbidden());
    mvc.perform(options("/api/auth/csrf").header("Origin", "https://attacker.example")
        .header("Access-Control-Request-Method", "GET"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  @Test
  void sessionLossRequiresSignInAndNewTokenRecoversWithoutReload() throws Exception {
    var old = token(null);
    login(old, "Transport test password!").andExpect(status().isOk());
    old.session().invalidate();
    mvc.perform(post("/api/private").header("X-CSRF-TOKEN", old.value()))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    var fresh = token(null);
    mvc.perform(post("/api/private").session(fresh.session()).header("X-CSRF-TOKEN", fresh.value()))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    login(fresh, "Transport test password!").andExpect(status().isOk());
  }
}
