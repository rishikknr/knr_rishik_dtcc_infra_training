package com.trace.backend.integration.connectors;

import com.trace.backend.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
  private final byte[] key;

  public CredentialCipher(@Value("${trace.connectors.encryption-key:}") String encoded) {
    if (encoded.isBlank()) {
      key = null;
      return;
    }
    try {
      key = Base64.getDecoder().decode(encoded);
      if (key.length != 32) throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Connector encryption key must be base64 of 32 random bytes");
    }
  }

  public record Encrypted(byte[] ciphertext, byte[] nonce) {}

  private byte[] crypt(int mode, UUID org, UUID connection, byte[] input, byte[] nonce) {
    if (key == null)
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ENCRYPTION_UNAVAILABLE",
          "Connector encryption is not configured. Manual imports remain available.");
    try {
      var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      c.updateAAD(("trace:v1:" + org + ":" + connection).getBytes(StandardCharsets.UTF_8));
      return c.doFinal(input);
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "CREDENTIAL_UNAVAILABLE",
          "Stored credentials cannot be opened with the configured key");
    }
  }

  public Encrypted encrypt(UUID org, UUID id, byte[] input) {
    var n = new byte[12];
    new SecureRandom().nextBytes(n);
    return new Encrypted(crypt(Cipher.ENCRYPT_MODE, org, id, input, n), n);
  }

  public byte[] decrypt(UUID org, UUID id, byte[] input, byte[] nonce) {
    return crypt(Cipher.DECRYPT_MODE, org, id, input, nonce);
  }
}
