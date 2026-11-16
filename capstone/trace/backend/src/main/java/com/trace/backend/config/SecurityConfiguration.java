package com.trace.backend.config;

import com.trace.backend.repository.IdentityRepository;
import com.trace.backend.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder(@Value("${trace.security.bcrypt-strength:12}") int strength) {
    if (strength < 10 || strength > 16)
      throw new IllegalArgumentException("BCrypt strength must be 10–16");
    return new BCryptPasswordEncoder(strength);
  }

  @Bean
  UserDetailsService users(IdentityRepository identities) {
    return email -> {
      if (email == null || email.length() > 320)
        throw new UsernameNotFoundException("Invalid credentials");
      var c =
          identities
              .credentials(email)
              .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
      return new TracePrincipal(c.id(), c.email(), c.passwordHash(), c.active(), c.version());
    };
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http, IdentityRepository identities, AuthenticationRateLimiter limiter)
      throws Exception {
    http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/registration",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/invitations/accept",
                        "/api/auth/password/reset",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .requestCache(c -> c.disable())
        .csrf(
            c ->
                c.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    // /csrf returns the masked request token, not the raw session token.
                    .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
        .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) ->
                            SecurityResponses.error(
                                r, 401, "AUTHENTICATION_REQUIRED", "Your session has expired. Please sign in again."))
                    .accessDeniedHandler(
                        (q, r, x) ->
                            SecurityResponses.error(
                                r,
                                403,
                                x instanceof CsrfException ? "CSRF_INVALID" : "ACCESS_DENIED",
                                x instanceof CsrfException
                                    ? "Your security token has expired. Please try again."
                                    : "You do not have permission to perform this action.")))
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/auth/login")
                    .usernameParameter("email")
                    .passwordParameter("password")
                    .successHandler(
                        (q, r, a) -> {
                          try {
                            identities.login(((TracePrincipal) a.getPrincipal()).id());
                            SecurityResponses.success(r);
                          } catch (org.springframework.dao.DataAccessException failure) {
                            com.trace.backend.service.IdentityService.invalidate(q);
                            SecurityResponses.error(
                                r,
                                503,
                                "SECURITY_AUDIT_UNAVAILABLE",
                                "Sign in is temporarily unavailable");
                          }
                        })
                    .failureHandler(
                        (q, r, e) -> {
                          identities.audit(null, null, "LOGIN_FAILED", "USER", null, "FAILURE");
                          SecurityResponses.error(
                              r, 401, "INVALID_CREDENTIALS", "Incorrect email or password.");
                        }))
        .logout(
            l ->
                l.logoutUrl("/api/auth/logout")
                    .addLogoutHandler(
                        (q, r, a) -> {
                          if (a != null && a.getPrincipal() instanceof TracePrincipal p)
                            identities.audit(p.id(), null, "LOGOUT", "USER", p.id(), "SUCCESS");
                        })
                    .logoutSuccessHandler((q, r, a) -> SecurityResponses.success(r))
                    .deleteCookies("JSESSIONID"))
        .addFilterBefore(new AuthenticationAbuseFilter(limiter), CsrfFilter.class)
        .addFilterBefore(new AccountStateFilter(identities), AuthorizationFilter.class);
    return http.build();
  }
}
