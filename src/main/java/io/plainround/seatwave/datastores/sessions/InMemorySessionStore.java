package io.plainround.seatwave.datastores.sessions;

import com.github.sviperll.result4j.Result;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A JDK-only model of {@link RedisSessionStore}: the same keys, the same Redis commands in the same
 * order, with time-to-live on keys and on hash fields, over plain maps. It counts every command by
 * its Redis name, as Redis's {@code INFO commandstats} does, so a test can prove which commands
 * each access pattern sends. Time comes from the injected clock, so expiry is testable without
 * waiting.
 *
 * <p>Thread-safety: every public method is synchronized; this is a teaching model, and production
 * code uses Redis, whose single command thread gives the same one-at-a-time behavior.
 */
public final class InMemorySessionStore implements SessionStore {

  private final Policy policy;
  private final Clock clock;
  // A Redis string with a TTL: key, value and the instant the key expires.
  private final Map<String, String> strings = new HashMap<>();
  private final Map<String, Instant> stringExpiry = new HashMap<>();
  // A Redis hash whose fields each carry a TTL (HPEXPIRE): key, field, value and field expiry.
  private final Map<String, Map<String, String>> hashes = new HashMap<>();
  private final Map<String, Map<String, Instant>> fieldExpiry = new HashMap<>();
  private final Map<String, Long> commandCounts = new TreeMap<>();

  /** Creates an empty store that applies {@code policy} and reads time from {@code clock}. */
  public InMemorySessionStore(Policy policy, Clock clock) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized Result<FanSession, Problem> create(FanSession session) {
    String indexKey = SessionKeys.fanIndexKey(session.fanId());
    if (hlen(indexKey) >= policy.maxSessionsPerFan()) {
      return Result.error(new TooManySessions(session.fanId(), policy.maxSessionsPerFan()));
    }
    Instant now = clock.instant();
    Instant expiresAt = now.plus(policy.expiryAfter(session, now));
    setIfAbsent(SessionKeys.sessionKey(session.sessionId()), session.toStoredValue(), expiresAt);
    hset(indexKey, session.sessionId(), session.device());
    hpexpire(indexKey, session.sessionId(), expiresAt);
    return Result.success(session);
  }

  @Override
  public synchronized Optional<FanSession> find(String sessionId) {
    count("GET");
    String key = SessionKeys.sessionKey(sessionId);
    dropIfExpired(key);
    return Optional.ofNullable(strings.get(key))
        .map(storedValue -> FanSession.fromStoredValue(sessionId, storedValue));
  }

  @Override
  public synchronized Result<FanSession, Problem> touch(
      FanSession current, List<String> basketHoldIds, Instant now) {
    if (!policy.allowsActivity(current, now)) {
      delete(current);
      return Result.error(new LifetimeOver(current.sessionId(), current.createdAt()));
    }
    FanSession updated = current.withActivity(basketHoldIds, now);
    Instant expiresAt = now.plus(policy.expiryAfter(updated, now));
    // SET ... XX: write only if the key still exists, so a session that expired or was signed
    // out between the read and this write is never brought back.
    if (!setIfPresent(
        SessionKeys.sessionKey(current.sessionId()), updated.toStoredValue(), expiresAt)) {
      return Result.error(new SessionGone(current.sessionId()));
    }
    hpexpire(SessionKeys.fanIndexKey(current.fanId()), current.sessionId(), expiresAt);
    return Result.success(updated);
  }

  @Override
  public synchronized void delete(FanSession session) {
    del(SessionKeys.sessionKey(session.sessionId()));
    hdel(SessionKeys.fanIndexKey(session.fanId()), session.sessionId());
  }

  @Override
  public synchronized List<String> sessionIdsOfFan(String fanId) {
    return hkeys(SessionKeys.fanIndexKey(fanId));
  }

  @Override
  public synchronized int deleteAllOfFan(String fanId) {
    String indexKey = SessionKeys.fanIndexKey(fanId);
    List<String> sessionIds = hkeys(indexKey);
    int deleted = 0;
    // One DEL per key, not one DEL with many keys: on a Redis cluster the sessions of one fan
    // live in different hash slots, and a multi-key DEL across slots is refused (Module 18).
    for (String sessionId : sessionIds) {
      deleted += Math.toIntExact(del(SessionKeys.sessionKey(sessionId)));
    }
    del(indexKey);
    return deleted;
  }

  /** Returns a copy of the number of commands sent so far, by Redis command name. */
  public synchronized Map<String, Long> commandCounts() {
    return Map.copyOf(commandCounts);
  }

  /** Returns how many session items are live now; a model-only view used by the demos. */
  public synchronized long liveSessionCount() {
    Instant now = clock.instant();
    return stringExpiry.values().stream().filter(expiry -> expiry.isAfter(now)).count();
  }

  private void setIfAbsent(String key, String value, Instant expiresAt) {
    count("SET");
    dropIfExpired(key);
    if (!strings.containsKey(key)) {
      strings.put(key, value);
      stringExpiry.put(key, expiresAt);
    }
  }

  private boolean setIfPresent(String key, String value, Instant expiresAt) {
    count("SET");
    dropIfExpired(key);
    if (!strings.containsKey(key)) {
      return false;
    }
    strings.put(key, value);
    stringExpiry.put(key, expiresAt);
    return true;
  }

  private long del(String key) {
    count("DEL");
    dropIfExpired(key);
    boolean existed = strings.remove(key) != null | hashes.remove(key) != null;
    stringExpiry.remove(key);
    fieldExpiry.remove(key);
    return existed ? 1 : 0;
  }

  private void hset(String key, String field, String value) {
    count("HSET");
    hashes.computeIfAbsent(key, ignored -> new HashMap<>()).put(field, value);
  }

  private void hpexpire(String key, String field, Instant expiresAt) {
    count("HPEXPIRE");
    if (hashes.getOrDefault(key, Map.of()).containsKey(field)) {
      fieldExpiry.computeIfAbsent(key, ignored -> new HashMap<>()).put(field, expiresAt);
    }
  }

  private void hdel(String key, String field) {
    count("HDEL");
    removeField(key, field);
  }

  private long hlen(String key) {
    count("HLEN");
    dropExpiredFields(key);
    return hashes.getOrDefault(key, Map.of()).size();
  }

  private List<String> hkeys(String key) {
    count("HKEYS");
    dropExpiredFields(key);
    return new ArrayList<>(hashes.getOrDefault(key, Map.of()).keySet());
  }

  // Redis expires a key passively when it is touched after its TTL; the model does the same.
  private void dropIfExpired(String key) {
    Instant expiry = stringExpiry.get(key);
    if (expiry != null && !expiry.isAfter(clock.instant())) {
      strings.remove(key);
      stringExpiry.remove(key);
    }
  }

  private void dropExpiredFields(String key) {
    Instant now = clock.instant();
    Map<String, Instant> expiries = fieldExpiry.getOrDefault(key, Map.of());
    List<String> expired =
        expiries.entrySet().stream()
            .filter(entry -> !entry.getValue().isAfter(now))
            .map(Map.Entry::getKey)
            .toList();
    expired.forEach(field -> removeField(key, field));
  }

  // A hash whose last field is gone is no key at all in Redis; the model drops it the same way.
  private void removeField(String key, String field) {
    Map<String, String> fields = hashes.get(key);
    if (fields == null) {
      return;
    }
    fields.remove(field);
    fieldExpiry.getOrDefault(key, new HashMap<>()).remove(field);
    if (fields.isEmpty()) {
      hashes.remove(key);
      fieldExpiry.remove(key);
    }
  }

  private void count(String redisCommand) {
    commandCounts.merge(redisCommand, 1L, Long::sum);
  }
}
