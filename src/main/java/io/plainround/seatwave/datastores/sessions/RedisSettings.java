package io.plainround.seatwave.datastores.sessions;

import com.github.sviperll.result4j.Result;
import io.lettuce.core.RedisURI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where the lab's Redis is and how to sign in to it, read once at start-up from environment
 * variables. Every problem is reported together, as a value, so a learner fixes the settings in one
 * go and never sees a stack trace for a typing mistake. Passwords are read from the files {@code
 * scripts/make-acl.sh} wrote, never from the code. Immutable and thread-safe.
 *
 * @param host the Redis host
 * @param port the published port, 21500 by default (Module 15's range is 21500 to 21599)
 * @param commandTimeout every command's deadline, so a stuck Redis fails a request quickly
 * @param appUser the session service's Redis user, whose ACL allows only S1 to S5's commands
 * @param appPasswordFile the file holding that user's generated password
 * @param opsUser the monitoring user, allowed {@code INFO} and nothing else
 * @param opsPasswordFile the file holding the monitoring user's password
 */
public record RedisSettings(
    String host,
    int port,
    Duration commandTimeout,
    String appUser,
    Path appPasswordFile,
    String opsUser,
    Path opsPasswordFile) {

  private static final int DEFAULT_PORT = 21_500;
  private static final int HIGHEST_PORT = 65_535;
  // Module 15's p99 target for a session read is 5 ms; a 250 ms deadline turns a stuck
  // connection into a fast 503 instead of a pile of waiting requests.
  private static final long DEFAULT_TIMEOUT_MILLIS = 250;
  // A deadline longer than this is no deadline for a request that must answer in milliseconds.
  private static final long MOST_TIMEOUT_MILLIS = 10_000;

  /**
   * Rejects missing values and a port or timeout out of range; {@link #fromEnvironment} checks them
   * first.
   */
  public RedisSettings {
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(commandTimeout, "commandTimeout");
    Objects.requireNonNull(appUser, "appUser");
    Objects.requireNonNull(appPasswordFile, "appPasswordFile");
    Objects.requireNonNull(opsUser, "opsUser");
    Objects.requireNonNull(opsPasswordFile, "opsPasswordFile");
    if (port < 1 || port > HIGHEST_PORT) {
      throw new IllegalArgumentException("port must be 1 to " + HIGHEST_PORT + ", was " + port);
    }
    if (commandTimeout.isNegative() || commandTimeout.isZero()) {
      throw new IllegalArgumentException("commandTimeout must be positive");
    }
  }

  /**
   * Reads the settings.
   *
   * @return the settings, or every problem found at once (never only the first)
   */
  public static Result<RedisSettings, List<String>> fromEnvironment(
      Map<String, String> environment) {
    List<String> problems = new ArrayList<>();
    int port =
        parsePort(environment.getOrDefault("SEATWAVE_REDIS_PORT", "" + DEFAULT_PORT), problems);
    long timeoutMillis =
        parsePositive(
            environment.getOrDefault("SEATWAVE_REDIS_TIMEOUT_MS", "" + DEFAULT_TIMEOUT_MILLIS),
            "SEATWAVE_REDIS_TIMEOUT_MS",
            problems);
    if (timeoutMillis > MOST_TIMEOUT_MILLIS) {
      problems.add(
          "SEATWAVE_REDIS_TIMEOUT_MS must be at most "
              + MOST_TIMEOUT_MILLIS
              + ", was "
              + timeoutMillis);
    }
    String host = environment.getOrDefault("SEATWAVE_REDIS_HOST", "127.0.0.1").strip();
    if (host.isEmpty()) {
      problems.add("SEATWAVE_REDIS_HOST must not be empty");
    }
    Path appPasswordFile =
        Path.of(
            environment.getOrDefault(
                "SEATWAVE_REDIS_APP_PASSWORD_FILE", ".secrets/redis/app.password"));
    Path opsPasswordFile =
        Path.of(
            environment.getOrDefault(
                "SEATWAVE_REDIS_OPS_PASSWORD_FILE", ".secrets/redis/ops.password"));
    for (Path passwordFile : List.of(appPasswordFile, opsPasswordFile)) {
      if (!Files.isReadable(passwordFile)) {
        problems.add(
            "password file "
                + passwordFile.toAbsolutePath()
                + " cannot be read: run scripts/stack.sh up --run, which writes it");
      }
    }
    if (!problems.isEmpty()) {
      return Result.error(List.copyOf(problems));
    }
    return Result.success(
        new RedisSettings(
            host,
            port,
            Duration.ofMillis(timeoutMillis),
            "seatwave_sessions",
            appPasswordFile,
            "seatwave_ops",
            opsPasswordFile));
  }

  /** The address of the session service's connection. */
  public RedisURI appUri() {
    return uriFor(appUser, appPasswordFile);
  }

  /** The address of the monitoring connection, used only to read {@code INFO commandstats}. */
  public RedisURI opsUri() {
    return uriFor(opsUser, opsPasswordFile);
  }

  private RedisURI uriFor(String user, Path passwordFile) {
    try {
      String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
      return RedisURI.builder()
          .withHost(host)
          .withPort(port)
          .withAuthentication(user, password)
          .withTimeout(commandTimeout)
          .build();
    } catch (IOException unreadable) {
      throw new UncheckedIOException(
          "cannot read the password file " + passwordFile.toAbsolutePath(), unreadable);
    }
  }

  private static int parsePort(String text, List<String> problems) {
    long port = parsePositive(text, "SEATWAVE_REDIS_PORT", problems);
    if (port > HIGHEST_PORT) {
      problems.add("SEATWAVE_REDIS_PORT must be at most " + HIGHEST_PORT + ", was " + text);
    }
    return (int) Math.min(port, HIGHEST_PORT);
  }

  private static long parsePositive(String text, String name, List<String> problems) {
    try {
      long value = Long.parseLong(text.strip());
      if (value < 1) {
        problems.add(name + " must be positive, was " + text);
      }
      return value;
    } catch (NumberFormatException notANumber) {
      problems.add(name + " must be a whole number, was '" + text + "'");
      return 1;
    }
  }
}
