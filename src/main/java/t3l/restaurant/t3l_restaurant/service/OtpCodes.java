package t3l.restaurant.t3l_restaurant.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class OtpCodes {
  private final SecureRandom random = new SecureRandom();
  private final SecretKeySpec secret = new SecretKeySpec(random.generateSeed(32), "HmacSHA256");

  String generate() {
    return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
  }

  boolean matches(String id, byte[] hash, String code) {
    return code != null && code.matches("[0-9]{6}") && MessageDigest.isEqual(hash, digest(id, code));
  }

  byte[] digest(String id, String code) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(secret);
      return mac.doFinal((id + ":" + code).getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
