package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The module's one invariant: every access pattern on the list is served by key lookups, never a
 * scan, and sends exactly the commands the list names. Runs on the JDK-only model; the same {@code
 * runEveryPattern} runs on Redis in Section 8, where the counts come from Redis itself.
 */
class AccessPatternTest {

  // A fixed seed is enough here: the session ids only have to be valid and distinct.
  private static final long SEED = 15L;
  private static final int SESSIONS_OF_FAN_7 = 3;

  // One generator per test instance (JUnit makes one instance per test), so ids never repeat.
  private final SecureRandom random = seededRandom();

  private final InMemorySessionStore store =
      new InMemorySessionStore(
          AccessPatternCheckCli.POLICY, Clock.fixed(AccessPatternCheckCli.START, ZoneOffset.UTC));

  @Test
  void everyListedPatternSendsExactlyTheCommandsTheListNames() {
    Map<SessionAccessPattern, Map<String, Long>> sent =
        AccessPatternCheckCli.runEveryPattern(
            store, store::commandCounts, random, AccessPatternCheckCli.START);

    assertThat(sent).containsOnlyKeys(SessionAccessPattern.values());
    sent.forEach((pattern, commands) ->
        assertThat(commands).as(pattern.name()).isEqualTo(pattern.allowedCommands()));
  }

  @Test
  void noPatternSendsAScanOrAKeysCommand() {
    AccessPatternCheckCli.runEveryPattern(
        store, store::commandCounts, random, AccessPatternCheckCli.START);

    assertThat(store.commandCounts()).doesNotContainKeys("SCAN", "KEYS", "HSCAN", "SSCAN");
  }

  @Test
  void signOutEverywhereRemovesEverySessionOfTheFanAndNoOther() {
    FanSession other =
        AccessPatternCheckCli.newSession("fan-8", "web", random, AccessPatternCheckCli.START);
    store.create(other);
    for (int sessionNumber = 0; sessionNumber < SESSIONS_OF_FAN_7; sessionNumber++) {
      store.create(
          AccessPatternCheckCli.newSession("fan-7", "ios", random, AccessPatternCheckCli.START));
    }

    int deleted = store.deleteAllOfFan("fan-7");

    assertThat(deleted).isEqualTo(SESSIONS_OF_FAN_7);
    assertThat(store.sessionIdsOfFan("fan-7")).isEmpty();
    assertThat(store.sessionIdsOfFan("fan-8")).isEqualTo(List.of(other.sessionId()));
  }

  private static SecureRandom seededRandom() {
    try {
      SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
      random.setSeed(SEED);
      return random;
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("the JDK always ships SHA1PRNG", missing);
    }
  }
}
