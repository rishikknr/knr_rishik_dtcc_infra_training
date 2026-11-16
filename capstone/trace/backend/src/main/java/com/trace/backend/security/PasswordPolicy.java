package com.trace.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PasswordPolicy {
  private final int minimum;

  public PasswordPolicy(@Value("${trace.security.password-min-length:12}") int minimum) {
    if (minimum < 12 || minimum > 64)
      throw new IllegalArgumentException("Password minimum must be 12–64");
    this.minimum = minimum;
  }

  public int minimumLength() {
    return minimum;
  }

  public String validate(String password) {
    if (password == null
        || password.length() < minimum
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Password must contain at least " + minimum + " characters and at most 72 UTF-8 bytes");
    return password;
  }

  public static String email(String email) {
    if (email != null) email = email.trim();
    if (email == null || email.length() > 320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid email required");
    return email.trim().toLowerCase(Locale.ROOT);
  }

  public static String label(String text, int limit) {
    if (text == null
        || text.isBlank()
        || text.length() > limit
        || text.chars().anyMatch(c -> c < 32))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid name required");
    return text.trim();
  }

  public static String slug(String text) {
    if (text == null || !text.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || text.length() > 100)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use a lowercase slug with letters, numbers and hyphens");
    return text;
  }
}
