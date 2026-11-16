package com.trace.backend;

import static org.junit.jupiter.api.Assertions.*;

import com.trace.backend.exception.ApiException;
import com.trace.backend.integration.connectors.CredentialCipher;
import java.util.*;
import org.junit.jupiter.api.Test;

class CredentialCipherTests {
  private final CredentialCipher cipher =
      new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

  @Test
  void authenticatedRoundTripAndFreshNonces() {
    var org = UUID.randomUUID();
    var id = UUID.randomUUID();
    byte[] secret = "test-secret".getBytes();
    var a = cipher.encrypt(org, id, secret);
    var b = cipher.encrypt(org, id, secret);
    assertArrayEquals(secret, cipher.decrypt(org, id, a.ciphertext(), a.nonce()));
    assertFalse(Arrays.equals(a.nonce(), b.nonce()));
    assertFalse(Arrays.equals(a.ciphertext(), secret));
  }

  @Test
  void identityBindingRejectsOtherOrganizationsAndConnections() {
    var org = UUID.randomUUID();
    var id = UUID.randomUUID();
    var a = cipher.encrypt(org, id, "secret".getBytes());
    assertThrows(
        ApiException.class, () -> cipher.decrypt(UUID.randomUUID(), id, a.ciphertext(), a.nonce()));
    assertThrows(
        ApiException.class,
        () -> cipher.decrypt(org, UUID.randomUUID(), a.ciphertext(), a.nonce()));
  }

  @Test
  void authenticationRejectsCiphertextTampering() {
    var org = UUID.randomUUID();
    var id = UUID.randomUUID();
    var a = cipher.encrypt(org, id, "secret".getBytes());
    a.ciphertext()[0] ^= 1;
    assertThrows(ApiException.class, () -> cipher.decrypt(org, id, a.ciphertext(), a.nonce()));
  }

  @Test
  void absentKeyFailsClosed() {
    assertThrows(
        ApiException.class,
        () -> new CredentialCipher("").encrypt(UUID.randomUUID(), UUID.randomUUID(), new byte[1]));
    assertThrows(IllegalStateException.class, () -> new CredentialCipher("weak"));
  }
}
