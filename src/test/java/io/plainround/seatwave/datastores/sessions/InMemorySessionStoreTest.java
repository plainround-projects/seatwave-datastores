package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.sviperll.result4j.Result;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The session rules on the JDK-only model: the idle timeout, the maximum lifetime, the per-fan
 * limit and the touch that never revives a signed-out session. Time is moved by hand.
 */
class InMemorySessionStoreTest {

  private static final Instant START = AccessPatternCheckCli.START;
  private static final SessionStore.Policy POLICY = AccessPatternCheckCli.POLICY;
  private static final String HOLD = "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a01";
  private static final Duration JUST_UNDER_IDLE = Duration.ofMinutes(29);
  private static final Duration PAST_IDLE = Duration.ofMinutes(31);

  private final AccessPatternCheckCli.AdjustableClock clock =
      new AccessPatternCheckCli.AdjustableClock(START);
  private final InMemorySessionStore store = new InMemorySessionStore(POLICY, clock);
  private final SecureRandom random = new SecureRandom();

  @Test
  void findsASessionUntilItsIdleTimeoutAndNotAfter() {
    FanSession session = signIn("fan-42");

    clock.advance(JUST_UNDER_IDLE);
    boolean foundBefore = store.find(session.sessionId()).isPresent();
    clock.advance(PAST_IDLE.minus(JUST_UNDER_IDLE));
    boolean foundAfter = store.find(session.sessionId()).isPresent();

    assertThat(foundBefore).isTrue();
    assertThat(foundAfter).isFalse();
  }

  @Test
  void theFanIndexForgetsASessionWhenItExpires() {
    signIn("fan-42");

    clock.advance(PAST_IDLE);

    assertThat(store.sessionIdsOfFan("fan-42")).isEmpty();
  }

  @Test
  void aTouchSlidesTheIdleTimeoutAndStoresTheBasket() {
    FanSession session = signIn("fan-42");
    clock.advance(JUST_UNDER_IDLE);

    store.touch(session, List.of(HOLD), clock.instant());
    clock.advance(JUST_UNDER_IDLE);
    Optional<FanSession> found = store.find(session.sessionId());

    assertThat(found).map(FanSession::basketHoldIds).contains(List.of(HOLD));
  }

  @Test
  void aTouchAfterSignOutIsRefusedAndDoesNotReviveTheSession() {
    FanSession session = signIn("fan-42");
    store.delete(session);

    Result<FanSession, SessionStore.Problem> touched =
        store.touch(session, List.of(), clock.instant());

    assertThat(problemOf(touched)).isEqualTo(new SessionStore.SessionGone(session.sessionId()));
    assertThat(store.find(session.sessionId())).isEmpty();
  }

  @Test
  void aTouchAtTheMaximumLifetimeIsRefusedAndRemovesTheSession() {
    FanSession session = signIn("fan-42");
    Instant atLifetimeEnd = START.plus(POLICY.maxLifetime());

    Result<FanSession, SessionStore.Problem> touched =
        store.touch(session, List.of(), atLifetimeEnd);

    assertThat(problemOf(touched))
        .isEqualTo(new SessionStore.LifetimeOver(session.sessionId(), START));
    assertThat(store.find(session.sessionId())).isEmpty();
    assertThat(store.sessionIdsOfFan("fan-42")).isEmpty();
  }

  @Test
  void refusesASignInBeyondThePerFanLimitAndKeepsTheOthers() {
    for (int device = 0; device < POLICY.maxSessionsPerFan(); device++) {
      signIn("fan-42");
    }

    Result<FanSession, SessionStore.Problem> refused =
        store.create(AccessPatternCheckCli.newSession("fan-42", "web", random, START));

    assertThat(problemOf(refused))
        .isEqualTo(new SessionStore.TooManySessions("fan-42", POLICY.maxSessionsPerFan()));
    assertThat(store.sessionIdsOfFan("fan-42")).hasSize(POLICY.maxSessionsPerFan());
  }

  @Test
  void signingOutASessionThatIsAlreadyGoneIsNotAnError() {
    FanSession session = signIn("fan-42");
    store.delete(session);

    store.delete(session);

    assertThat(store.sessionIdsOfFan("fan-42")).isEmpty();
  }

  @Test
  void countsLiveSessionsOnly() {
    signIn("fan-42");
    clock.advance(PAST_IDLE);
    signIn("fan-43");

    assertThat(store.liveSessionCount()).isEqualTo(1);
  }

  @Test
  void theDemoClockRefusesAnotherZone() {
    assertThatThrownBy(() -> clock.withZone(ZoneId.of("Europe/Paris")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
  }

  private FanSession signIn(String fanId) {
    FanSession session = AccessPatternCheckCli.newSession(fanId, "web", random, clock.instant());
    assertThat(store.create(session).isError()).isFalse();
    return session;
  }

  private static SessionStore.Problem problemOf(Result<FanSession, SessionStore.Problem> result) {
    AtomicReference<SessionStore.Problem> problem = new AtomicReference<>();
    result.peekError(problem::set);
    assertThat(problem.get()).as("expected a refusal, got a session").isNotNull();
    return problem.get();
  }
}
