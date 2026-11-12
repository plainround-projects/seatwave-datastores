package io.plainround.seatwave.datastores.sessions;

import com.github.sviperll.result4j.Result;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * An open-loop load test of the session store at Module 15's estimated peak: S1 reads and S3
 * touches arrive on a fixed schedule whether or not earlier calls have finished, and each call's
 * latency is measured from the moment it was due, so a slow store cannot hide its queue (the
 * mistake Module 4 calls coordinated omission). By default it drives the lab Redis and also reports
 * what Redis itself measured: its main thread's CPU, the time it spent per command and the memory
 * the preloaded sessions take. {@code --store=model} drives the in-memory model, which checks the
 * harness, not Redis.
 */
public final class SessionLoadTestCli {

  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final long TICK_NANOS = 1_000_000L;
  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double BYTES_PER_MIB = 1_024.0 * 1_024.0;
  private static final double PERCENT = 100.0;
  private static final double P50 = 0.50;
  private static final double P99 = 0.99;
  // Room for the slowest calls to finish after the last one is sent, before results are read.
  private static final long DRAIN_SECONDS = 30;
  // Every preloaded basket is full, so the measured memory is that of the largest stored value.
  private static final int BASKET_HOLDS = FanSession.MAX_BASKET_HOLDS;
  private static final int SETTINGS_PROBLEM_EXIT = 2;
  private static final int FAILED_RUN_EXIT = 1;

  private SessionLoadTestCli() {}

  /** Entry point; see the class comment for the two stores and the README for the settings. */
  public static void main(String[] arguments) throws InterruptedException {
    Optional<LoadSettings> load =
        report(LoadSettings.fromEnvironment(System.getenv()), "load settings");
    if (load.isEmpty()) {
      System.exit(SETTINGS_PROBLEM_EXIT);
      return;
    }
    Clock clock = Clock.systemUTC();
    boolean clean;
    if (List.of(arguments).contains("--store=model")) {
      SessionStore model = new InMemorySessionStore(AccessPatternCheckCli.POLICY, clock);
      clean =
          run(
              model,
              () -> "",
              load.get(),
              clock,
              "the in-memory model (checks the harness, not Redis)");
    } else {
      Optional<RedisSettings> settings =
          report(RedisSettings.fromEnvironment(System.getenv()), "Redis settings");
      if (settings.isEmpty()) {
        System.exit(SETTINGS_PROBLEM_EXIT);
        return;
      }
      clean = runOnRedis(settings.get(), load.get(), clock);
    }
    System.exit(clean ? 0 : FAILED_RUN_EXIT);
  }

  /** Prints every problem of a settings result and returns the settings, if there were none. */
  static <T> Optional<T> report(Result<T, List<String>> settings, String what) {
    return settings
        .peekError(problems -> System.err.println(what + ": " + String.join("; ", problems)))
        .discardError();
  }

  private static boolean runOnRedis(RedisSettings settings, LoadSettings load, Clock clock)
      throws InterruptedException {
    RedisClient client = RedisClient.create();
    try (StatefulRedisConnection<String, String> app = client.connect(settings.appUri());
        StatefulRedisConnection<String, String> ops = client.connect(settings.opsUri())) {
      RedisCommands<String, String> monitoring = ops.sync();
      SessionStore redis = new RedisSessionStore(app.sync(), AccessPatternCheckCli.POLICY, clock);
      return run(
          redis,
          () ->
              monitoring.info("cpu")
                  + monitoring.info("commandstats")
                  + monitoring.info("memory")
                  + monitoring.info("keyspace"),
          load,
          clock,
          "Redis at " + settings.host() + ":" + settings.port());
    } finally {
      client.shutdown();
    }
  }

  /**
   * Preloads the sessions, runs the schedule, prints one line per pattern and, when {@code info}
   * returns Redis's INFO text, what Redis measured. Returns whether every scheduled call completed.
   */
  private static boolean run(
      SessionStore store, Supplier<String> info, LoadSettings load, Clock clock, String target)
      throws InterruptedException {
    System.out.println("Load test of the session store on " + target);
    try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
      Semaphore inFlight = new Semaphore(load.maxInFlight());
      String emptyInfo = info.get();
      List<FanSession> sessions = preload(store, load, clock, workers, inFlight);
      String loadedInfo = info.get();
      if (!loadedInfo.isEmpty()) {
        printMemory(ServerStats.parse(emptyInfo), ServerStats.parse(loadedInfo), sessions);
      }
      Recorder reads = new Recorder("S1 read", load.readsPerSecond() * load.seconds());
      Recorder touches = new Recorder("S3 touch", load.touchesPerSecond() * load.seconds());
      long start = System.nanoTime();
      Schedule schedule = new Schedule(store, clock, sessions, workers, inFlight);
      schedule.send(load, reads, touches, start);
      if (inFlight.tryAcquire(load.maxInFlight(), DRAIN_SECONDS, TimeUnit.SECONDS)) {
        inFlight.release(load.maxInFlight());
      } else {
        System.out.println(
            "  calls still running after the drain time; the results below omit them");
      }
      double wallSeconds = (double) (System.nanoTime() - start) / NANOS_PER_SECOND;
      String finishedInfo = info.get();
      boolean readsClean = reads.print(load.seconds());
      boolean touchesClean = touches.print(load.seconds());
      if (!finishedInfo.isEmpty()) {
        System.out.println(
            "  "
                + ServerStats.parse(finishedInfo)
                    .describeSince(ServerStats.parse(loadedInfo), wallSeconds));
      }
      return readsClean && touchesClean;
    }
  }

  private static List<FanSession> preload(
      SessionStore store,
      LoadSettings load,
      Clock clock,
      ExecutorService workers,
      Semaphore inFlight)
      throws InterruptedException {
    SecureRandom random = new SecureRandom();
    List<FanSession> sessions = new ArrayList<>(load.sessions());
    AtomicLong refused = new AtomicLong();
    for (int sessionNumber = 0; sessionNumber < load.sessions(); sessionNumber++) {
      List<String> basket = new ArrayList<>(BASKET_HOLDS);
      for (int holdNumber = 0; holdNumber < BASKET_HOLDS; holdNumber++) {
        basket.add(new UUID(random.nextLong(), random.nextLong()).toString());
      }
      FanSession session =
          new FanSession(
              SessionKeys.newSessionId(random),
              "fan-" + sessionNumber,
              "web",
              basket,
              clock.instant(),
              clock.instant());
      sessions.add(session);
      inFlight.acquire();
      workers.submit(() -> {
        try {
          if (store.create(session).isError()) {
            refused.incrementAndGet();
          }
        } finally {
          inFlight.release();
        }
      });
    }
    // Wait until every create has finished: take all permits, then give them back.
    inFlight.acquire(load.maxInFlight());
    inFlight.release(load.maxInFlight());
    if (refused.get() > 0) {
      throw new IllegalStateException(refused.get() + " preloaded sessions were refused");
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "  preloaded %,d sessions, each with %d holds in its basket; stored value %.1f bytes on average",
            load.sessions(),
            BASKET_HOLDS,
            averageValueBytes(sessions)));
    return List.copyOf(sessions);
  }

  private static double averageValueBytes(List<FanSession> sessions) {
    long valueBytes = 0;
    for (FanSession session : sessions) {
      valueBytes += session.toStoredValue().getBytes(StandardCharsets.UTF_8).length;
    }
    return (double) valueBytes / sessions.size();
  }

  private static void printMemory(
      ServerStats empty, ServerStats loaded, List<FanSession> sessions) {
    if (empty.keys() > 0) {
      // Earlier runs' sessions share the fan index hashes of this run's fans ("fan-0" and on),
      // so the difference below is not one new session's memory.
      System.out.println(
          String.format(
              Locale.ROOT,
              "  WARNING: Redis held %,d keys before the preload; restart the stack for a clean"
                  + " memory measurement",
              empty.keys()));
    }
    double bytesPerSession =
        (double) (loaded.usedMemoryBytes() - empty.usedMemoryBytes()) / sessions.size();
    System.out.println(
        String.format(
            Locale.ROOT,
            "  Redis used_memory %.1f MiB before the preload, %.1f MiB after: %.0f bytes per session"
                + " (key, value, expiry and fan index entry), %.0f beyond the stored value",
            empty.usedMemoryBytes() / BYTES_PER_MIB,
            loaded.usedMemoryBytes() / BYTES_PER_MIB,
            bytesPerSession,
            bytesPerSession - averageValueBytes(sessions)));
  }

  /** The scheduler: one thread that sends the due calls every 1 ms tick. */
  private record Schedule(
      SessionStore store,
      Clock clock,
      List<FanSession> sessions,
      ExecutorService workers,
      Semaphore inFlight) {

    // ponytail: one scheduler thread with 1 ms ticks; enough for about 50,000 calls a second.
    void send(LoadSettings load, Recorder reads, Recorder touches, long start)
        throws InterruptedException {
      long ticks = load.seconds() * NANOS_PER_SECOND / TICK_NANOS;
      long sentReads = 0;
      long sentTouches = 0;
      for (long tickNumber = 1; tickNumber <= ticks; tickNumber++) {
        long tickStart = start + (tickNumber - 1) * TICK_NANOS;
        // Integer arithmetic on the schedule: by the end of tick n exactly rate * n ms are due,
        // so a run of S seconds sends exactly rate * S calls of each pattern.
        long dueReads = load.readsPerSecond() * tickNumber * TICK_NANOS / NANOS_PER_SECOND;
        long dueTouches = load.touchesPerSecond() * tickNumber * TICK_NANOS / NANOS_PER_SECOND;
        for (; sentReads < dueReads; sentReads++) {
          FanSession session = pick();
          submit(reads, tickStart, () -> store.find(session.sessionId()).isPresent());
        }
        for (; sentTouches < dueTouches; sentTouches++) {
          FanSession session = pick();
          submit(
              touches,
              tickStart,
              () -> !store.touch(session, session.basketHoldIds(), clock.instant()).isError());
        }
        long sleepNanos = tickStart + TICK_NANOS - System.nanoTime();
        if (sleepNanos > 0) {
          TimeUnit.NANOSECONDS.sleep(sleepNanos);
        }
      }
    }

    private void submit(Recorder recorder, long dueNanos, BooleanSupplier call) {
      // Open loop: if too many calls are in flight, the call is counted as dropped, never delayed,
      // so the schedule stays honest and the drop count shows the store fell behind.
      if (!inFlight.tryAcquire()) {
        recorder.dropped.incrementAndGet();
        return;
      }
      workers.submit(() -> {
        try {
          if (call.getAsBoolean()) {
            recorder.record(System.nanoTime() - dueNanos);
          } else {
            recorder.fail("the session was missing or the touch was refused");
          }
        } catch (RuntimeException failed) {
          recorder.fail(failed.getClass().getSimpleName() + ": " + failed.getMessage());
        } finally {
          inFlight.release();
        }
      });
    }

    private FanSession pick() {
      return sessions.get(ThreadLocalRandom.current().nextInt(sessions.size()));
    }
  }

  /** Latencies of one pattern; thread-safe through atomic counters and a pre-sized array. */
  private static final class Recorder {
    private final String name;
    private final long[] latencyNanos;
    private final AtomicInteger recorded = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicReference<String> firstFailure = new AtomicReference<>("");

    Recorder(String name, long scheduledCalls) {
      this.name = name;
      this.latencyNanos = new long[Math.toIntExact(scheduledCalls)];
    }

    void record(long nanos) {
      int slot = recorded.getAndIncrement();
      if (slot < latencyNanos.length) {
        latencyNanos[slot] = nanos;
      }
    }

    void fail(String reason) {
      failed.incrementAndGet();
      firstFailure.compareAndSet("", reason);
    }

    /** Prints the pattern's line; returns whether every scheduled call completed. */
    boolean print(long seconds) {
      int completed = Math.min(recorded.get(), latencyNanos.length);
      long[] sorted = Arrays.copyOf(latencyNanos, completed);
      Arrays.sort(sorted);
      // Each scheduled call is done, failed, dropped or still running; the shares are of all of
      // them.
      long scheduled = latencyNanos.length;
      System.out.println(
          String.format(
              Locale.ROOT,
              "  %-9s %,10d of %,d done (%,.0f/s)  failed %,d  dropped %,d (%.2f%%)"
                  + "  p50 %.3f ms  p99 %.3f ms  max %.3f ms",
              name,
              completed,
              scheduled,
              (double) completed / seconds,
              failed.get(),
              dropped.get(),
              PERCENT * dropped.get() / scheduled,
              percentileMillis(sorted, P50),
              percentileMillis(sorted, P99),
              completed == 0 ? 0.0 : sorted[completed - 1] / NANOS_PER_MILLI));
      if (failed.get() > 0) {
        System.out.println("    first failure: " + firstFailure.get());
      }
      boolean clean = completed == scheduled;
      if (!clean) {
        System.out.println(
            "    WARNING: "
                + (scheduled - completed)
                + " scheduled calls did not complete; the latencies describe only those that did");
      }
      return clean;
    }

    private static double percentileMillis(long[] sorted, double quantile) {
      if (sorted.length == 0) {
        return 0.0;
      }
      int index = (int) Math.ceil(quantile * sorted.length) - 1;
      return sorted[Math.max(index, 0)] / NANOS_PER_MILLI;
    }
  }

  /**
   * The load, from environment variables; defaults are Module 15's estimated peak.
   *
   * @param readsPerSecond S1 calls per second, 33,333 by default
   * @param touchesPerSecond S3 calls per second, 13,333 by default
   * @param seconds how long the load runs, 60 by default
   * @param sessions how many sessions to preload (the lab uses a tenth of the 2 million)
   * @param maxInFlight calls allowed to wait at once before new ones count as dropped
   */
  record LoadSettings(
      long readsPerSecond, long touchesPerSecond, long seconds, int sessions, int maxInFlight) {

    private static final Map<String, Long> DEFAULTS =
        Map.of(
            "SEATWAVE_LOAD_READS_PER_SECOND", 33_333L,
            "SEATWAVE_LOAD_TOUCHES_PER_SECOND", 13_333L,
            "SEATWAVE_LOAD_SECONDS", 60L,
            "SEATWAVE_LOAD_SESSIONS", 200_000L,
            "SEATWAVE_LOAD_MAX_IN_FLIGHT", 2_048L);
    // The latency arrays hold rate x seconds entries; this keeps each under about 160 MB.
    private static final long MOST_CALLS_PER_PATTERN = 20_000_000L;

    /** Reads the five settings; returns every problem found at once, never only the first. */
    static Result<LoadSettings, List<String>> fromEnvironment(Map<String, String> environment) {
      List<String> problems = new ArrayList<>();
      Map<String, Long> values = new HashMap<>();
      DEFAULTS.forEach((name, fallback) -> {
        String text = environment.getOrDefault(name, Long.toString(fallback));
        try {
          long value = Long.parseLong(text.strip());
          if (value < 1 || value > MOST_CALLS_PER_PATTERN) {
            problems.add(
                name + " must be between 1 and " + MOST_CALLS_PER_PATTERN + ", was " + text);
          }
          values.put(name, value);
        } catch (NumberFormatException notANumber) {
          problems.add(name + " must be a whole number, was '" + text + "'");
        }
      });
      if (problems.isEmpty()
          && Math.max(
                      values.get("SEATWAVE_LOAD_READS_PER_SECOND"),
                      values.get("SEATWAVE_LOAD_TOUCHES_PER_SECOND"))
                  * values.get("SEATWAVE_LOAD_SECONDS")
              > MOST_CALLS_PER_PATTERN) {
        problems.add(
            "rate x seconds must stay at most " + MOST_CALLS_PER_PATTERN + " calls per pattern");
      }
      if (!problems.isEmpty()) {
        // Map.of has no order; sorted problems read the same on every run.
        problems.sort(String::compareTo);
        return Result.error(List.copyOf(problems));
      }
      return Result.success(
          new LoadSettings(
              values.get("SEATWAVE_LOAD_READS_PER_SECOND"),
              values.get("SEATWAVE_LOAD_TOUCHES_PER_SECOND"),
              values.get("SEATWAVE_LOAD_SECONDS"),
              Math.toIntExact(values.get("SEATWAVE_LOAD_SESSIONS")),
              Math.toIntExact(values.get("SEATWAVE_LOAD_MAX_IN_FLIGHT"))));
    }
  }
}
