package io.plainround.seatwave.datastores.sessions;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Runs every session access pattern once and prints the Redis commands it sent, against the
 * JDK-only model (the default) or against the lab's Redis ({@code --redis}), where the counts come
 * from Redis's own {@code INFO commandstats}. Then, on the model, it shows the idle timeout, the
 * maximum lifetime, and what the one query that is not on the list would cost.
 */
public final class AccessPatternCheckCli {

  /** Module 15's policy (Section 6a): 30 minutes idle, 12 hours at most, 10 devices per fan. */
  static final SessionStore.Policy POLICY =
      new SessionStore.Policy(Duration.ofMinutes(30), Duration.ofHours(12), 10);

  static final Instant START = Instant.parse("2026-11-20T09:00:00Z");
  private static final String HOLD_A = "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a01";
  private static final String HOLD_B = "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a02";
  private static final int SESSIONS_OF_THE_SIGNED_OUT_FAN = 3;
  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(20);
  // S3 runs a minute after sign-in, so the touch changes lastSeenAt.
  private static final Duration TOUCH_AFTER = Duration.ofMinutes(1);
  // The missing pattern: SCAN walks the whole keyspace, about this many keys per call by default.
  private static final long ACTIVE_SESSIONS = 2_000_000L;
  private static final long SCAN_KEYS_PER_CALL = 10L;
  private static final int SETTINGS_PROBLEM_EXIT = 2;

  private AccessPatternCheckCli() {}

  /** Entry point: {@code --redis} runs the patterns on the lab's Redis; otherwise on the model. */
  public static void main(String[] arguments) {
    boolean onRedis = List.of(arguments).contains("--redis");
    if (onRedis) {
      checkOnRedis();
      return;
    }
    AdjustableClock clock = new AdjustableClock(START);
    InMemorySessionStore store = new InMemorySessionStore(POLICY, clock);
    print("model", runEveryPattern(store, store::commandCounts, new SecureRandom(), START));
    showExpiry();
    showMissingPattern();
  }

  /**
   * Runs S1 to S5 once each and returns, per pattern, the commands it sent: the difference of the
   * counter before and after. A fan with three sessions is signed out everywhere in S5. The
   * sessions sign in at {@code now}, which must be the store's clock's time: a session signed in
   * long before its store's clock would have outlived its maximum lifetime.
   */
  static Map<SessionAccessPattern, Map<String, Long>> runEveryPattern(
      SessionStore store,
      Supplier<Map<String, Long>> commandCounter,
      SecureRandom random,
      Instant now) {
    Map<SessionAccessPattern, Map<String, Long>> sent = new EnumMap<>(SessionAccessPattern.class);
    FanSession session = newSession("fan-42", "web", random, now);
    sent.put(
        SessionAccessPattern.S2_CREATE_AT_SIGN_IN,
        measure(commandCounter, () -> store.create(session)));
    sent.put(
        SessionAccessPattern.S1_READ_BY_ID,
        measure(commandCounter, () -> store.find(session.sessionId())));
    sent.put(
        SessionAccessPattern.S3_TOUCH_ON_ACTIVITY,
        measure(
            commandCounter,
            () -> store.touch(session, List.of(HOLD_A, HOLD_B), now.plus(TOUCH_AFTER))));
    sent.put(
        SessionAccessPattern.S4_SIGN_OUT_ONE_DEVICE,
        measure(commandCounter, () -> store.delete(session)));
    for (int sessionNumber = 0; sessionNumber < SESSIONS_OF_THE_SIGNED_OUT_FAN; sessionNumber++) {
      store.create(newSession("fan-7", "ios", random, now));
    }
    sent.put(
        SessionAccessPattern.S5_SIGN_OUT_EVERYWHERE,
        measure(commandCounter, () -> store.deleteAllOfFan("fan-7")));
    return sent;
  }

  static FanSession newSession(
      String fanId, String device, SecureRandom random, Instant signedInAt) {
    return new FanSession(
        SessionKeys.newSessionId(random), fanId, device, List.of(), signedInAt, signedInAt);
  }

  private static Map<String, Long> measure(Supplier<Map<String, Long>> counter, Runnable pattern) {
    Map<String, Long> before = counter.get();
    pattern.run();
    Map<String, Long> difference = new HashMap<>(counter.get());
    difference.replaceAll((command, after) -> after - before.getOrDefault(command, 0L));
    // The monitoring user's own INFO call is not part of the pattern.
    difference.remove("INFO");
    difference.values().removeIf(calls -> calls == 0);
    return Map.copyOf(difference);
  }

  private static void print(String target, Map<SessionAccessPattern, Map<String, Long>> sent) {
    System.out.println("Access patterns of the session workload, run once each on the " + target);
    for (Map.Entry<SessionAccessPattern, Map<String, Long>> entry : sent.entrySet()) {
      SessionAccessPattern pattern = entry.getKey();
      boolean asListed = entry.getValue().equals(pattern.allowedCommands());
      System.out.println(
          String.format(
              Locale.ROOT,
              "  %-24s %,7d/s  p99 %2d ms  %-20s sent %-36s %s",
              pattern.name(),
              pattern.peakPerSecond(),
              pattern.p99TargetMillis(),
              pattern.consistency(),
              new TreeMap<>(entry.getValue()),
              asListed ? "as listed" : "NOT AS LISTED"));
    }
    long scans =
        sent.values().stream().mapToLong(commands -> commands.getOrDefault("SCAN", 0L)).sum();
    System.out.println("  scans sent by all patterns: " + scans);
  }

  private static void showExpiry() {
    AdjustableClock clock = new AdjustableClock(START);
    InMemorySessionStore store = new InMemorySessionStore(POLICY, clock);
    FanSession session = newSession("fan-42", "web", new SecureRandom(), START);
    store.create(session);
    System.out.println("Idle timeout (30 min) and maximum lifetime (12 h), on the model");
    clock.advance(Duration.ofMinutes(29));
    System.out.println(
        "  +29 min, no activity: found " + store.find(session.sessionId()).isPresent());
    store.touch(session, List.of(HOLD_A), clock.instant());
    clock.advance(Duration.ofMinutes(29));
    System.out.println(
        "  touched at +29 min with 1 hold, now +58 min: found "
            + store.find(session.sessionId()).isPresent());
    clock.advance(Duration.ofMinutes(2));
    System.out.println(
        "  no activity since +29 min, now +60 min: found "
            + store.find(session.sessionId()).isPresent()
            + ", the fan's index lists "
            + store.sessionIdsOfFan("fan-42").size()
            + " sessions");
    AdjustableClock busyClock = new AdjustableClock(START);
    InMemorySessionStore busyStore = new InMemorySessionStore(POLICY, busyClock);
    FanSession current = newSession("fan-43", "android", new SecureRandom(), START);
    busyStore.create(current);
    int acceptedTouches = 0;
    while (true) {
      busyClock.advance(TOUCH_INTERVAL);
      var outcome = busyStore.touch(current, List.of(), busyClock.instant());
      Optional<FanSession> touched = outcome.discardError();
      if (touched.isEmpty()) {
        long hours = Duration.between(START, busyClock.instant()).toHours();
        int accepted = acceptedTouches;
        outcome.peekError(problem ->
            System.out.println(
                "  a session used every 20 min: "
                    + accepted
                    + " touches accepted, refused at +"
                    + hours
                    + " h: "
                    + problem.getClass().getSimpleName()));
        System.out.println(
            "  after the refusal: found " + busyStore.find(current.sessionId()).isPresent());
        return;
      }
      current = touched.get();
      acceptedTouches++;
    }
  }

  private static void showMissingPattern() {
    long scanCalls = ACTIVE_SESSIONS / SCAN_KEYS_PER_CALL;
    System.out.println("Missing pattern: \"how many fans are viewing event 1 now?\"");
    System.out.println(
        "  no key leads to it; the only way is SCAN over every session key and a GET of each:");
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %,d SCAN calls (about %d keys each) + %,d GET calls for one answer",
            scanCalls,
            SCAN_KEYS_PER_CALL,
            ACTIVE_SESSIONS));
    System.out.println(
        "  and the session service's ACL forbids SCAN, so the query fails instead of running");
  }

  private static void checkOnRedis() {
    Optional<RedisSettings> found =
        SessionLoadTestCli.report(RedisSettings.fromEnvironment(System.getenv()), "Redis settings");
    if (found.isEmpty()) {
      System.exit(SETTINGS_PROBLEM_EXIT);
      return;
    }
    RedisSettings settings = found.get();
    RedisClient client = RedisClient.create();
    try (StatefulRedisConnection<String, String> app = client.connect(settings.appUri());
        StatefulRedisConnection<String, String> ops = client.connect(settings.opsUri())) {
      RedisCommands<String, String> monitoring = ops.sync();
      RedisSessionStore store = new RedisSessionStore(app.sync(), POLICY, Clock.systemUTC());
      print(
          "lab Redis",
          runEveryPattern(
              store,
              () -> commandStats(monitoring),
              new SecureRandom(),
              Clock.systemUTC().instant()));
    } finally {
      client.shutdown();
    }
  }

  /** Parses {@code cmdstat_get:calls=5,usec=...} lines into command name and call count. */
  static Map<String, Long> commandStats(RedisCommands<String, String> monitoring) {
    Map<String, Long> calls = new HashMap<>();
    for (String line : monitoring.info("commandstats").split("\r?\n")) {
      if (!line.startsWith("cmdstat_")) {
        continue;
      }
      String command =
          line.substring("cmdstat_".length(), line.indexOf(':')).toUpperCase(Locale.ROOT);
      String callsField = line.substring(line.indexOf("calls=") + "calls=".length());
      calls.put(command, Long.parseLong(callsField.substring(0, callsField.indexOf(','))));
    }
    return calls;
  }

  /** A clock the demo moves forward by hand; confined to the demo's one thread. */
  static final class AdjustableClock extends Clock {
    private Instant now;

    AdjustableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration step) {
      now = now.plus(step);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException("the demo clock is always UTC");
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
