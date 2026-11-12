package io.plainround.seatwave.datastores.sessions;

import java.util.Map;

/**
 * The access-pattern list of the session workload (Section 7 derives the rates). Each pattern names
 * the Redis commands it may send, and how many; a test runs every pattern and fails if the store
 * sent anything else, a scan above all.
 */
public enum SessionAccessPattern {
  S1_READ_BY_ID(
      "read the session on a request", 33_333, 5, "read your own writes", Map.of("GET", 1L)),
  S2_CREATE_AT_SIGN_IN(
      "create a session at sign-in",
      555,
      10,
      "read your own writes",
      Map.of("HLEN", 1L, "SET", 1L, "HSET", 1L, "HPEXPIRE", 1L)),
  S3_TOUCH_ON_ACTIVITY(
      "store the basket and slide the idle timeout",
      13_333,
      10,
      "read your own writes",
      Map.of("SET", 1L, "HPEXPIRE", 1L)),
  S4_SIGN_OUT_ONE_DEVICE(
      "delete one session", 56, 10, "gone at once", Map.of("DEL", 1L, "HDEL", 1L)),
  S5_SIGN_OUT_EVERYWHERE(
      "list a fan's sessions and delete them all (here: a fan with 3)",
      1,
      50,
      "gone at once",
      Map.of("HKEYS", 1L, "DEL", 4L));

  private final String description;
  private final int peakPerSecond;
  private final int p99TargetMillis;
  private final String consistency;
  private final Map<String, Long> allowedCommands;

  SessionAccessPattern(
      String description,
      int peakPerSecond,
      int p99TargetMillis,
      String consistency,
      Map<String, Long> allowedCommands) {
    this.description = description;
    this.peakPerSecond = peakPerSecond;
    this.p99TargetMillis = p99TargetMillis;
    this.consistency = consistency;
    this.allowedCommands = Map.copyOf(allowedCommands);
  }

  /** What the pattern does, in the words of the access-pattern list. */
  public String description() {
    return description;
  }

  /** The estimated peak rate, from Section 7's arithmetic (not measured). */
  public int peakPerSecond() {
    return peakPerSecond;
  }

  /**
   * The p99 target for one call of the pattern, store round trips included: a small share of Module
   * 2's 300 ms target for a hold, because every request pays the session read.
   */
  public int p99TargetMillis() {
    return p99TargetMillis;
  }

  /** The consistency the pattern needs, in the words of Module 19. */
  public String consistency() {
    return consistency;
  }

  /** The Redis commands one call may send, by name, with their exact counts. */
  public Map<String, Long> allowedCommands() {
    return allowedCommands;
  }
}
