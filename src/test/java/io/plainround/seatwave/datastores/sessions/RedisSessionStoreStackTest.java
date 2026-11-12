package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The session store against the lab Redis (scripts/stack.sh test): Redis itself counts the commands
 * of every pattern, the ACL refuses a scan and any key outside the two prefixes, a touch cannot
 * revive a signed-out session, and a session and its index field expire on one clock.
 */
@Tag("stack")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSessionStoreStackTest {

  // A policy with a 1-second idle timeout, so expiry is observed in the test's own time.
  private static final SessionStore.Policy SHORT_POLICY =
      new SessionStore.Policy(Duration.ofSeconds(1), Duration.ofSeconds(1), 10);
  private static final Duration EXPIRY_WAIT = Duration.ofSeconds(10);

  private final SecureRandom random = new SecureRandom();
  // Fresh fan ids on every run, so a rerun against the same Redis never meets an earlier run's
  // sessions (they live 30 minutes).
  private final String signedOutFan = freshFanId();
  private final String expiringFan = freshFanId();
  private final String everywhereFan = freshFanId();
  private final String otherFan = freshFanId();
  private RedisClient client;
  private StatefulRedisConnection<String, String> app;
  private StatefulRedisConnection<String, String> ops;

  @BeforeAll
  void connect() {
    AtomicReference<RedisSettings> found = new AtomicReference<>();
    RedisSettings.fromEnvironment(System.getenv())
        .peekError(problems -> assertThat(problems).as("Redis settings").isEmpty())
        .ifSuccess(found::set);
    client = RedisClient.create();
    app = client.connect(found.get().appUri());
    ops = client.connect(found.get().opsUri());
  }

  @AfterAll
  void disconnect() {
    app.close();
    ops.close();
    client.shutdown();
  }

  @Test
  void redisCountsExactlyTheListedCommandsForEveryPattern() {
    RedisSessionStore store =
        new RedisSessionStore(app.sync(), AccessPatternCheckCli.POLICY, Clock.systemUTC());

    Map<SessionAccessPattern, Map<String, Long>> sent =
        AccessPatternCheckCli.runEveryPattern(
            store,
            () -> AccessPatternCheckCli.commandStats(ops.sync()),
            random,
            Clock.systemUTC().instant());

    assertThat(sent).containsOnlyKeys(SessionAccessPattern.values());
    sent.forEach((pattern, commands) ->
        assertThat(commands).as(pattern.name()).isEqualTo(pattern.allowedCommands()));
  }

  @Test
  void theAclRefusesAScanToTheSessionService() {
    RedisCommands<String, String> sessions = app.sync();

    assertThatThrownBy(sessions::scan)
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageStartingWith("NOPERM");
  }

  @Test
  void theAclRefusesTheSessionServiceAKeyOutsideItsPrefixes() {
    RedisCommands<String, String> sessions = app.sync();

    assertThatThrownBy(() -> sessions.get("seatwave:ratelimit:fan-42"))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageStartingWith("NOPERM");
  }

  @Test
  void theAclRefusesTheMonitoringUserEverySessionKey() {
    RedisCommands<String, String> monitoring = ops.sync();

    assertThatThrownBy(() -> monitoring.get("seatwave:session:Xq3vN1rP8sTt0YwZ4aBcDe"))
        .isInstanceOf(RedisCommandExecutionException.class)
        .hasMessageStartingWith("NOPERM");
  }

  @Test
  void aTouchAfterSignOutIsRefusedAndDoesNotReviveTheSession() {
    RedisSessionStore store =
        new RedisSessionStore(app.sync(), AccessPatternCheckCli.POLICY, Clock.systemUTC());
    FanSession session =
        AccessPatternCheckCli.newSession(signedOutFan, "web", random, Clock.systemUTC().instant());
    store.create(session);
    store.delete(session);

    boolean refused = store.touch(session, List.of(), Clock.systemUTC().instant()).isError();

    assertThat(refused).isTrue();
    assertThat(store.find(session.sessionId())).isEmpty();
    assertThat(store.sessionIdsOfFan(signedOutFan)).isEmpty();
  }

  @Test
  void aSessionAndItsIndexFieldExpireTogether() {
    Clock clock = Clock.systemUTC();
    RedisSessionStore store = new RedisSessionStore(app.sync(), SHORT_POLICY, clock);
    FanSession session =
        new FanSession(
            SessionKeys.newSessionId(random),
            expiringFan,
            "ios",
            List.of(),
            clock.instant(),
            clock.instant());
    store.create(session);
    assertThat(store.sessionIdsOfFan(expiringFan)).containsExactly(session.sessionId());

    await()
        .atMost(EXPIRY_WAIT)
        .until(() ->
            store.find(session.sessionId()).isEmpty()
                && store.sessionIdsOfFan(expiringFan).isEmpty());
  }

  @Test
  void signOutEverywhereRemovesEverySessionOfTheFanAndNoOther() {
    RedisSessionStore store =
        new RedisSessionStore(app.sync(), AccessPatternCheckCli.POLICY, Clock.systemUTC());
    FanSession other =
        AccessPatternCheckCli.newSession(otherFan, "web", random, Clock.systemUTC().instant());
    store.create(other);
    store.create(
        AccessPatternCheckCli.newSession(
            everywhereFan, "ios", random, Clock.systemUTC().instant()));
    store.create(
        AccessPatternCheckCli.newSession(
            everywhereFan, "web", random, Clock.systemUTC().instant()));

    int deleted = store.deleteAllOfFan(everywhereFan);

    assertThat(deleted).isEqualTo(2);
    assertThat(store.sessionIdsOfFan(everywhereFan)).isEmpty();
    assertThat(store.sessionIdsOfFan(otherFan)).containsExactly(other.sessionId());
  }

  private String freshFanId() {
    return "fan-" + Long.toUnsignedString(random.nextLong(), Character.MAX_RADIX);
  }
}
