package io.plainround.seatwave.datastores.sessions;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The few numbers of Redis's {@code INFO cpu}, {@code INFO commandstats}, {@code INFO memory} and
 * {@code INFO keyspace} that the load test reports: CPU time of the main thread, which executes
 * every command, and the calls and microseconds per command. Immutable and thread-safe.
 *
 * @param mainThreadCpuSeconds {@code used_cpu_user_main_thread + used_cpu_sys_main_thread}
 * @param usedMemoryBytes {@code used_memory}: the bytes Redis's allocator holds for data and itself
 * @param keys the keys in all databases ({@code keys=} of each {@code db} line), 0 when empty
 * @param callsByCommand {@code calls=} per command, lower case as Redis names them
 * @param microsByCommand {@code usec=} per command: time spent executing it, queueing excluded
 */
public record ServerStats(
    double mainThreadCpuSeconds,
    long usedMemoryBytes,
    long keys,
    Map<String, Long> callsByCommand,
    Map<String, Long> microsByCommand) {

  private static final Pattern FIELD =
      Pattern.compile("^([a-z_]+):([0-9.]+)\\r?$", Pattern.MULTILINE);
  private static final Pattern DATABASE =
      Pattern.compile("^db\\d+:keys=(\\d+),", Pattern.MULTILINE);
  private static final Pattern COMMAND =
      Pattern.compile("^cmdstat_([a-z|_-]+):calls=(\\d+),usec=(\\d+),", Pattern.MULTILINE);
  // The monitoring user's own INFO calls are not part of the workload.
  private static final String MONITORING_COMMAND = "info";

  /** Copies the maps, so a snapshot cannot change after it was taken. */
  public ServerStats {
    Objects.requireNonNull(callsByCommand, "callsByCommand");
    Objects.requireNonNull(microsByCommand, "microsByCommand");
    callsByCommand = Map.copyOf(callsByCommand);
    microsByCommand = Map.copyOf(microsByCommand);
  }

  /**
   * Reads the numbers from the text of {@code INFO cpu}, {@code INFO commandstats}, {@code INFO
   * memory} and {@code INFO keyspace}, in any order.
   *
   * @throws IllegalStateException if a field the load test needs is missing: the text did not come
   *     from a Redis that reports per-thread CPU (6.2 and later)
   */
  public static ServerStats parse(String infoText) {
    Map<String, String> fields = new TreeMap<>();
    Matcher field = FIELD.matcher(infoText);
    while (field.find()) {
      fields.put(field.group(1), field.group(2));
    }
    long keys = 0;
    Matcher database = DATABASE.matcher(infoText);
    while (database.find()) {
      keys += Long.parseLong(database.group(1));
    }
    Map<String, Long> calls = new TreeMap<>();
    Map<String, Long> micros = new TreeMap<>();
    Matcher command = COMMAND.matcher(infoText);
    while (command.find()) {
      calls.put(command.group(1), Long.parseLong(command.group(2)));
      micros.put(command.group(1), Long.parseLong(command.group(3)));
    }
    return new ServerStats(
        Double.parseDouble(required(fields, "used_cpu_user_main_thread"))
            + Double.parseDouble(required(fields, "used_cpu_sys_main_thread")),
        Long.parseLong(required(fields, "used_memory")),
        keys,
        calls,
        micros);
  }

  /** Commands executed since {@code earlier}, the monitoring user's own {@code INFO} excluded. */
  public long commandsSince(ServerStats earlier) {
    return difference(callsByCommand, earlier.callsByCommand);
  }

  /** Microseconds spent executing those commands since {@code earlier}. */
  public long commandMicrosSince(ServerStats earlier) {
    return difference(microsByCommand, earlier.microsByCommand);
  }

  /** One line for the report: thread busy share over {@code wallSeconds} and µs per command. */
  public String describeSince(ServerStats earlier, double wallSeconds) {
    long commands = commandsSince(earlier);
    double busyPercent =
        100.0 * (mainThreadCpuSeconds - earlier.mainThreadCpuSeconds) / wallSeconds;
    return String.format(
        Locale.ROOT,
        "Redis main thread %.1f%% busy over %.1f s; %,d commands (%,.0f/s), %.2f us each to execute",
        busyPercent,
        wallSeconds,
        commands,
        commands / wallSeconds,
        commands == 0 ? 0.0 : (double) commandMicrosSince(earlier) / commands);
  }

  private static long difference(Map<String, Long> later, Map<String, Long> earlier) {
    long total = 0;
    for (Map.Entry<String, Long> entry : later.entrySet()) {
      if (!entry.getKey().equals(MONITORING_COMMAND)) {
        total += entry.getValue() - earlier.getOrDefault(entry.getKey(), 0L);
      }
    }
    return total;
  }

  private static String required(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (value == null) {
      throw new IllegalStateException("INFO has no " + name + " field");
    }
    return value;
  }
}
