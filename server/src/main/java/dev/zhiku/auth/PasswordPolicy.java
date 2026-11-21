package dev.zhiku.auth;

import dev.zhiku.platform.ApiException;
import java.security.SecureRandom;

/** 本地账号的密码规则与一次性临时密码。规则刻意简单：长度足够、不等于用户名、不是单一字符重复。 */
public final class PasswordPolicy {
  public static final int MIN = 10;
  public static final int MAX = 200;
  /** 去掉 0/O、1/l/I 等易混字符，方便口头或纸面转交。 */
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private PasswordPolicy() {}

  public static void check(String username, String password) {
    if (password == null || password.length() < MIN || password.length() > MAX) {
      throw ApiException.unprocessable("Password must be " + MIN + "-" + MAX + " characters");
    }
    if (password.strip().equalsIgnoreCase(username == null ? "" : username.strip())) {
      throw ApiException.unprocessable("Password must not be the username");
    }
    if (password.chars().distinct().count() < 4) throw ApiException.unprocessable("Password is too simple");
  }

  public static String temporary() {
    var out = new StringBuilder(16);
    for (int i = 0; i < 16; i++) out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    return out.toString();
  }
}
