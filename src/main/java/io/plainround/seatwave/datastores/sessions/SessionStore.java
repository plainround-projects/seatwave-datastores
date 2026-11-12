package io.plainround.seatwave.datastores.sessions;

import com.github.sviperll.result4j.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The session workload's five access patterns and nothing else. Each method is one key lookup or a
 * short, bounded sequence of them, so no implementation needs a scan; a query that is not in this
 * list has no method, which is the point. Implementations are thread-safe.
 */
public interface SessionStore {

  /** S2, sign-in: stores a new session; refuses a fan who already has the maximum. */
  Result<FanSession, Problem> create(FanSession session);

  /** S1, every request: the session by its id, or empty if it never existed or has expired. */
  Optional<FanSession> find(String sessionId);

  /**
   * S3, activity: stores the new basket and slides the idle timeout; refuses a session that has
   * expired, was signed out, or has lived past its maximum lifetime (and then removes it).
   */
  Result<FanSession, Problem> touch(FanSession current, List<String> basketHoldIds, Instant now);

  /** S4, sign-out on one device. Deleting a session that is already gone is not an error. */
  void delete(FanSession session);

  /** S5, "your devices": the ids of a fan's live sessions, at most the policy's maximum. */
  List<String> sessionIdsOfFan(String fanId);

  /** S5, "sign out everywhere": deletes every live session of a fan and returns how many. */
  int deleteAllOfFan(String fanId);

  /**
   * The session rules, from configuration.
   *
   * @param idleTimeout a session not used for this long expires (the time-to-live, slid on use)
   * @param maxLifetime no session lives longer than this, however busy, so a stolen session id
   *     cannot be kept alive for ever by using it
   * @param maxSessionsPerFan sign-ins beyond this are refused, which bounds S5's work
   */
  record Policy(Duration idleTimeout, Duration maxLifetime, int maxSessionsPerFan) {
    public Policy {
      Objects.requireNonNull(idleTimeout, "idleTimeout");
      Objects.requireNonNull(maxLifetime, "maxLifetime");
      if (idleTimeout.isNegative() || idleTimeout.isZero()) {
        throw new IllegalArgumentException("idleTimeout must be positive");
      }
      if (maxLifetime.compareTo(idleTimeout) < 0) {
        throw new IllegalArgumentException("maxLifetime must be at least idleTimeout");
      }
      if (maxSessionsPerFan < 1) {
        throw new IllegalArgumentException("maxSessionsPerFan must be at least 1");
      }
    }

    /** Whether a request at {@code now} may still use the session. */
    public boolean allowsActivity(FanSession session, Instant now) {
      return now.isBefore(session.createdAt().plus(maxLifetime));
    }

    /**
     * The time-to-live to set after activity at {@code now}: the idle timeout, cut short so the key
     * never outlives the session's maximum lifetime. Callers check {@link #allowsActivity} first,
     * so the result is always positive.
     */
    public Duration expiryAfter(FanSession session, Instant now) {
      Duration untilLifetimeEnds = Duration.between(now, session.createdAt().plus(maxLifetime));
      return untilLifetimeEnds.compareTo(idleTimeout) < 0 ? untilLifetimeEnds : idleTimeout;
    }
  }

  /** The expected refusals of the session workload. */
  sealed interface Problem permits TooManySessions, LifetimeOver, SessionGone {}

  /** The fan already has the policy's maximum of live sessions. */
  record TooManySessions(String fanId, int limit) implements Problem {}

  /** The session reached its maximum lifetime; the fan must sign in again. */
  record LifetimeOver(String sessionId, Instant createdAt) implements Problem {}

  /** The session expired or was signed out between the read and the write. */
  record SessionGone(String sessionId) implements Problem {}
}
