package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.sviperll.result4j.Result;
import io.lettuce.core.RedisCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Redis and load settings: every problem is reported together, as a value, and valid settings
 * are read as given. Each test breaks one setting, or all of them, and nothing else.
 */
class SettingsTest {

  private static final long PEAK_READS = 33_333L;
  private static final long PEAK_TOUCHES = 13_333L;
  private static final long DEFAULT_SECONDS = 60L;
  private static final int DEFAULT_SESSIONS = 200_000;
  private static final int DEFAULT_IN_FLIGHT = 2_048;
  private static final int DEFAULT_PORT = 21_500;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(250);

  @TempDir Path folder;

  @Test
  void readsTheDefaultsWhenThePasswordFilesExist() throws IOException {
    RedisSettings settings = success(RedisSettings.fromEnvironment(passwordFiles()));

    assertThat(settings.host()).isEqualTo("127.0.0.1");
    assertThat(settings.port()).isEqualTo(DEFAULT_PORT);
    assertThat(settings.commandTimeout()).isEqualTo(DEFAULT_TIMEOUT);
    assertThat(settings.appUser()).isEqualTo("seatwave_sessions");
    assertThat(settings.opsUser()).isEqualTo("seatwave_ops");
  }

  @Test
  void buildsBothAddressesWithTheirUsersAndPasswords() throws IOException {
    RedisSettings settings = success(RedisSettings.fromEnvironment(passwordFiles()));

    // Lettuce 7 keeps the user and password behind a credentials provider, not on the URI.
    RedisCredentials app =
        settings
            .appUri()
            .getCredentialsProviderAsync()
            .resolveCredentialsAsync()
            .toCompletableFuture()
            .join();
    RedisCredentials ops =
        settings
            .opsUri()
            .getCredentialsProviderAsync()
            .resolveCredentialsAsync()
            .toCompletableFuture()
            .join();

    assertThat(app.getUsername()).isEqualTo("seatwave_sessions");
    assertThat(String.valueOf(app.getPassword())).isEqualTo("app-secret");
    assertThat(ops.getUsername()).isEqualTo("seatwave_ops");
    assertThat(String.valueOf(ops.getPassword())).isEqualTo("ops-secret");
    assertThat(settings.opsUri().getTimeout()).isEqualTo(DEFAULT_TIMEOUT);
  }

  @Test
  void reportsEveryRedisProblemTogether() {
    Map<String, String> environment =
        Map.of(
            "SEATWAVE_REDIS_PORT", "70000",
            "SEATWAVE_REDIS_TIMEOUT_MS", "soon",
            "SEATWAVE_REDIS_HOST", " ",
            "SEATWAVE_REDIS_APP_PASSWORD_FILE", folder.resolve("missing-app").toString(),
            "SEATWAVE_REDIS_OPS_PASSWORD_FILE", folder.resolve("missing-ops").toString());

    List<String> problems = problems(RedisSettings.fromEnvironment(environment));

    assertThat(problems)
        .hasSize(5)
        .anySatisfy(
            problem -> assertThat(problem).contains("SEATWAVE_REDIS_PORT must be at most 65535"))
        .anySatisfy(problem ->
            assertThat(problem)
                .contains("SEATWAVE_REDIS_TIMEOUT_MS must be a whole number, was 'soon'"))
        .anySatisfy(
            problem -> assertThat(problem).contains("SEATWAVE_REDIS_HOST must not be empty"))
        .anySatisfy(problem -> assertThat(problem).contains("missing-app cannot be read"))
        .anySatisfy(problem -> assertThat(problem).contains("missing-ops cannot be read"));
  }

  @Test
  void refusesAZeroPort() throws IOException {
    Map<String, String> environment = with(passwordFiles(), "SEATWAVE_REDIS_PORT", "0");

    assertThat(problems(RedisSettings.fromEnvironment(environment)))
        .containsExactly("SEATWAVE_REDIS_PORT must be positive, was 0");
  }

  @Test
  void refusesATimeoutLongerThanTenSeconds() throws IOException {
    Map<String, String> environment = with(passwordFiles(), "SEATWAVE_REDIS_TIMEOUT_MS", "10001");

    assertThat(problems(RedisSettings.fromEnvironment(environment)))
        .containsExactly("SEATWAVE_REDIS_TIMEOUT_MS must be at most 10000, was 10001");
  }

  @Test
  void readsTheLoadDefaultsAsTheEstimatedPeak() {
    SessionLoadTestCli.LoadSettings load =
        success(SessionLoadTestCli.LoadSettings.fromEnvironment(Map.of()));

    assertThat(load)
        .isEqualTo(
            new SessionLoadTestCli.LoadSettings(
                PEAK_READS, PEAK_TOUCHES, DEFAULT_SECONDS, DEFAULT_SESSIONS, DEFAULT_IN_FLIGHT));
  }

  @Test
  void reportsEveryLoadProblemTogether() {
    Map<String, String> environment =
        Map.of(
            "SEATWAVE_LOAD_READS_PER_SECOND", "-1",
            "SEATWAVE_LOAD_SECONDS", "a minute");

    assertThat(problems(SessionLoadTestCli.LoadSettings.fromEnvironment(environment)))
        .containsExactly(
            "SEATWAVE_LOAD_READS_PER_SECOND must be between 1 and 20000000, was -1",
            "SEATWAVE_LOAD_SECONDS must be a whole number, was 'a minute'");
  }

  @Test
  void refusesMoreCallsThanTheLatencyArraysHold() {
    // 33,333 a second for 601 seconds is 20,033,133 calls, over the 20,000,000 cap.
    Map<String, String> environment = Map.of("SEATWAVE_LOAD_SECONDS", "601");

    assertThat(problems(SessionLoadTestCli.LoadSettings.fromEnvironment(environment)))
        .containsExactly("rate x seconds must stay at most 20000000 calls per pattern");
  }

  private Map<String, String> passwordFiles() throws IOException {
    Path app =
        Files.writeString(folder.resolve("app.password"), "app-secret\n", StandardCharsets.UTF_8);
    Path ops =
        Files.writeString(folder.resolve("ops.password"), "ops-secret\n", StandardCharsets.UTF_8);
    return Map.of(
        "SEATWAVE_REDIS_APP_PASSWORD_FILE", app.toString(),
        "SEATWAVE_REDIS_OPS_PASSWORD_FILE", ops.toString());
  }

  private static Map<String, String> with(
      Map<String, String> environment, String name, String value) {
    HashMap<String, String> changed = new HashMap<>(environment);
    changed.put(name, value);
    return Map.copyOf(changed);
  }

  private static <T> T success(Result<T, List<String>> result) {
    return result
        .peekError(problems -> assertThat(problems).as("settings problems").isEmpty())
        .discardError()
        .orElseThrow();
  }

  private static <T> List<String> problems(Result<T, List<String>> result) {
    AtomicReference<List<String>> found = new AtomicReference<>(List.of());
    result.peekError(found::set);
    assertThat(result.isError()).as("expected settings problems").isTrue();
    return found.get();
  }
}
