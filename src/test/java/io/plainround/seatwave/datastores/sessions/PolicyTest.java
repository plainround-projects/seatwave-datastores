package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The session policy: its own limits, and the expiry that never outlives the maximum lifetime. */
class PolicyTest {

  private static final Duration IDLE = Duration.ofMinutes(30);
  private static final Duration LIFETIME = Duration.ofHours(12);
  private static final int DEVICES = 10;
  private static final Instant START = Instant.parse("2026-11-20T09:00:00Z");
  private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
  private static final FanSession SESSION =
      new FanSession("Xq3vN1rP8sTt0YwZ4aBcDe", "fan-42", "web", List.of(), START, START);

  private final SessionStore.Policy policy = new SessionStore.Policy(IDLE, LIFETIME, DEVICES);

  @Test
  void expiryIsTheIdleTimeoutWhileTheLifetimeIsFarAway() {
    assertThat(policy.expiryAfter(SESSION, START)).isEqualTo(IDLE);
  }

  @Test
  void expiryIsCutShortSoTheKeyNeverOutlivesTheLifetime() {
    Instant tenMinutesBeforeTheEnd = START.plus(LIFETIME).minus(TEN_MINUTES);

    assertThat(policy.expiryAfter(SESSION, tenMinutesBeforeTheEnd)).isEqualTo(TEN_MINUTES);
  }

  @Test
  void allowsActivityUntilTheLifetimeEndsAndNotAtIt() {
    Instant end = START.plus(LIFETIME);

    assertThat(policy.allowsActivity(SESSION, end.minusMillis(1))).isTrue();
    assertThat(policy.allowsActivity(SESSION, end)).isFalse();
  }

  @Test
  void rejectsAZeroIdleTimeout() {
    assertThatThrownBy(() -> new SessionStore.Policy(Duration.ZERO, LIFETIME, DEVICES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("idleTimeout must be positive");
  }

  @Test
  void rejectsALifetimeShorterThanTheIdleTimeout() {
    assertThatThrownBy(() -> new SessionStore.Policy(IDLE, IDLE.minusMillis(1), DEVICES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxLifetime must be at least idleTimeout");
  }

  @Test
  void rejectsZeroSessionsPerFan() {
    assertThatThrownBy(() -> new SessionStore.Policy(IDLE, LIFETIME, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxSessionsPerFan must be at least 1");
  }
}
