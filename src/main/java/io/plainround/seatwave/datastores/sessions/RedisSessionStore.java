package io.plainround.seatwave.datastores.sessions;

import com.github.sviperll.result4j.Result;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The session workload in Redis. Each session is one string key with a time-to-live; each fan has
 * one hash whose fields are that fan's session ids, each field with the same time-to-live as its
 * session, in milliseconds (HPEXPIRE, Redis 7.4 and later), so the index never lists a session that
 * has expired.
 *
 * <p>Thread-safety: thread-safe. It holds one Lettuce connection's synchronous commands, which many
 * threads may share; the connection is created once by the caller and closed by the caller.
 *
 * <p>Every method may throw {@link io.lettuce.core.RedisException} when Redis cannot be reached in
 * the connection's timeout or refuses a command (for example {@code NOPERM} from the ACL); the
 * service boundary turns that into 503, because a session that cannot be read is not a guest.
 */
public final class RedisSessionStore implements SessionStore {

  private final RedisCommands<String, String> redis;
  private final Policy policy;
  private final Clock clock;

  /** Wraps an open connection's commands; does not take ownership of the connection. */
  public RedisSessionStore(RedisCommands<String, String> redis, Policy policy, Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Result<FanSession, Problem> create(FanSession session) {
    String indexKey = SessionKeys.fanIndexKey(session.fanId());
    // The limit is a guard against abuse, not a rule like "one seat, one owner": two sign-ins
    // of one fan in the same millisecond may both pass this check, which costs one extra session.
    if (redis.hlen(indexKey) >= policy.maxSessionsPerFan()) {
      return Result.error(new TooManySessions(session.fanId(), policy.maxSessionsPerFan()));
    }
    Duration expiry = policy.expiryAfter(session, clock.instant());
    // NX: a new session never overwrites an existing key; with 128 random bits a clash means
    // a broken random source, so it is a bug, not a business outcome.
    String reply =
        redis.set(
            SessionKeys.sessionKey(session.sessionId()),
            session.toStoredValue(),
            SetArgs.Builder.nx().px(expiry.toMillis()));
    if (reply == null) {
      throw new IllegalStateException("session id already in use: the random source is broken");
    }
    redis.hset(indexKey, session.sessionId(), session.device());
    // HPEXPIRE, not HEXPIRE: Lettuce sends a Duration to HEXPIRE in whole seconds, so the field
    // would expire up to a second before its session, and at once when less than a second is
    // left. The stack test found it; the session key's PX is in milliseconds, and so is this.
    redis.hpexpire(indexKey, expiry, session.sessionId());
    return Result.success(session);
  }

  @Override
  public Optional<FanSession> find(String sessionId) {
    return Optional.ofNullable(redis.get(SessionKeys.sessionKey(sessionId)))
        .map(storedValue -> FanSession.fromStoredValue(sessionId, storedValue));
  }

  @Override
  public Result<FanSession, Problem> touch(
      FanSession current, List<String> basketHoldIds, Instant now) {
    if (!policy.allowsActivity(current, now)) {
      delete(current);
      return Result.error(new LifetimeOver(current.sessionId(), current.createdAt()));
    }
    FanSession updated = current.withActivity(basketHoldIds, now);
    Duration expiry = policy.expiryAfter(updated, now);
    // XX: only if the key still exists. Value and TTL change in one command, so there is no
    // moment in which a signed-out session could be written back (a plain SET would revive it).
    String reply =
        redis.set(
            SessionKeys.sessionKey(current.sessionId()),
            updated.toStoredValue(),
            SetArgs.Builder.xx().px(expiry.toMillis()));
    if (reply == null) {
      return Result.error(new SessionGone(current.sessionId()));
    }
    redis.hpexpire(SessionKeys.fanIndexKey(current.fanId()), expiry, current.sessionId());
    return Result.success(updated);
  }

  @Override
  public void delete(FanSession session) {
    redis.del(SessionKeys.sessionKey(session.sessionId()));
    redis.hdel(SessionKeys.fanIndexKey(session.fanId()), session.sessionId());
  }

  @Override
  public List<String> sessionIdsOfFan(String fanId) {
    return List.copyOf(redis.hkeys(SessionKeys.fanIndexKey(fanId)));
  }

  @Override
  public int deleteAllOfFan(String fanId) {
    String indexKey = SessionKeys.fanIndexKey(fanId);
    int deleted = 0;
    // One DEL per session: a fan's sessions sit in different hash slots of a Redis cluster, and
    // a multi-key DEL across slots is refused there (Module 18).
    for (String sessionId : redis.hkeys(indexKey)) {
      deleted += Math.toIntExact(redis.del(SessionKeys.sessionKey(sessionId)));
    }
    redis.del(indexKey);
    return deleted;
  }
}
