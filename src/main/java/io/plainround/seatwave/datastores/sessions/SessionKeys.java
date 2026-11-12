package io.plainround.seatwave.datastores.sessions;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The key design of the session workload, in one place: every access pattern S1 to S5 reaches its
 * data through one of these two keys, so no pattern needs a scan. Stateless and thread-safe.
 */
public final class SessionKeys {

  /** Prefix of the item that holds one session; the Redis user's ACL allows only these keys. */
  public static final String SESSION_PREFIX = "seatwave:session:";

  /** Prefix of the per-fan index: one hash per fan, one field per live session of that fan. */
  public static final String FAN_INDEX_PREFIX = "seatwave:fan-sessions:";

  // 16 bytes = 128 bits of randomness: guessing a live session among 2 million is hopeless.
  private static final int SESSION_ID_BYTES = 16;
  private static final Base64.Encoder URL_SAFE = Base64.getUrlEncoder().withoutPadding();

  private SessionKeys() {}

  /** Returns a new, unguessable session id of 22 URL-safe characters. */
  public static String newSessionId(SecureRandom random) {
    byte[] idBytes = new byte[SESSION_ID_BYTES];
    random.nextBytes(idBytes);
    return URL_SAFE.encodeToString(idBytes);
  }

  /**
   * Returns the key of one session's item.
   *
   * @throws IllegalArgumentException if the id is not a session id, so input from a cookie can
   *     never name a key outside the session prefix
   */
  public static String sessionKey(String sessionId) {
    if (sessionId == null || !FanSession.SESSION_ID.matcher(sessionId).matches()) {
      throw new IllegalArgumentException("not a session id");
    }
    return SESSION_PREFIX + sessionId;
  }

  /** Returns the key of a fan's session index; rejects anything that is not a fan id. */
  public static String fanIndexKey(String fanId) {
    if (fanId == null || !FanSession.FAN_ID.matcher(fanId).matches()) {
      throw new IllegalArgumentException("not a fan id");
    }
    return FAN_INDEX_PREFIX + fanId;
  }
}
