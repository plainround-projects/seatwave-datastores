package io.plainround.seatwave.datastores.sessions;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One signed-in fan on one device: the state a stateless SeatWave instance (Module 10) must not
 * keep in its own memory. Immutable and therefore thread-safe; the compact constructor rejects
 * every invalid value, so an invalid session cannot exist.
 *
 * @param sessionId 22 characters of URL-safe Base64 (16 random bytes), the item's key
 * @param fanId the token subject of Module 11, for example {@code fan-42}
 * @param device one of {@link #DEVICES}
 * @param basketHoldIds the fan's live holds, at most {@link #MAX_BASKET_HOLDS}, each a hold UUID
 * @param createdAt when the fan signed in on this device
 * @param lastSeenAt the last request on this session, never before {@code createdAt}
 */
public record FanSession(
    String sessionId,
    String fanId,
    String device,
    List<String> basketHoldIds,
    Instant createdAt,
    Instant lastSeenAt) {

  /** Module 13's rule: a fan holds at most four seats at a time, so the basket never needs more. */
  public static final int MAX_BASKET_HOLDS = 4;

  /** The devices the sign-in endpoint accepts (an allow-list, not a free-text field). */
  public static final Set<String> DEVICES = Set.of("web", "ios", "android");

  /** 16 random bytes in URL-safe Base64 without padding are exactly 22 characters. */
  static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{22}");

  /** Module 11's subjects: lower-case letters, digits and dashes, at most 64 characters. */
  static final Pattern FAN_ID = Pattern.compile("[a-z0-9-]{1,64}");

  // The stored value is one line of fields; the separators cannot occur inside any field,
  // because every field is checked against a pattern that excludes them.
  private static final String FIELD_SEPARATOR = "|";
  private static final String HOLD_SEPARATOR = ",";
  private static final String FORMAT_VERSION = "v1";
  private static final int STORED_FIELDS = 6;

  public FanSession {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(fanId, "fanId");
    Objects.requireNonNull(device, "device");
    Objects.requireNonNull(basketHoldIds, "basketHoldIds");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    if (!SESSION_ID.matcher(sessionId).matches()) {
      throw new IllegalArgumentException("sessionId must be 22 URL-safe Base64 characters");
    }
    if (!FAN_ID.matcher(fanId).matches()) {
      throw new IllegalArgumentException("fanId must be 1 to 64 of a-z, 0-9 and '-'");
    }
    if (!DEVICES.contains(device)) {
      throw new IllegalArgumentException("device must be one of " + DEVICES + ", was " + device);
    }
    if (basketHoldIds.size() > MAX_BASKET_HOLDS) {
      throw new IllegalArgumentException(
          "a basket holds at most " + MAX_BASKET_HOLDS + " holds, got " + basketHoldIds.size());
    }
    basketHoldIds.forEach(FanSession::requireHoldId);
    basketHoldIds = List.copyOf(basketHoldIds);
    if (lastSeenAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("lastSeenAt is before createdAt");
    }
  }

  /** Returns this session after a request at {@code now} that left the basket as given. */
  public FanSession withActivity(List<String> newBasketHoldIds, Instant now) {
    return new FanSession(sessionId, fanId, device, newBasketHoldIds, createdAt, now);
  }

  /** Returns the value stored under the session's key: one line of at most 251 bytes. */
  public String toStoredValue() {
    return String.join(
        FIELD_SEPARATOR,
        FORMAT_VERSION,
        fanId,
        device,
        String.join(HOLD_SEPARATOR, basketHoldIds),
        Long.toString(createdAt.toEpochMilli()),
        Long.toString(lastSeenAt.toEpochMilli()));
  }

  /**
   * Rebuilds a session from its stored value.
   *
   * @throws IllegalStateException if the stored value is not one this class wrote; that is
   *     corrupted data, an infrastructure failure, not an expected business outcome
   */
  public static FanSession fromStoredValue(String sessionId, String storedValue) {
    String[] fields = storedValue.split(Pattern.quote(FIELD_SEPARATOR), -1);
    if (fields.length != STORED_FIELDS || !FORMAT_VERSION.equals(fields[0])) {
      throw new IllegalStateException("stored session " + sessionId + " has an unknown format");
    }
    try {
      List<String> holds =
          fields[3].isEmpty() ? List.of() : Arrays.asList(fields[3].split(HOLD_SEPARATOR));
      return new FanSession(
          sessionId,
          fields[1],
          fields[2],
          holds,
          Instant.ofEpochMilli(Long.parseLong(fields[4])),
          Instant.ofEpochMilli(Long.parseLong(fields[5])));
    } catch (IllegalArgumentException invalidField) {
      throw new IllegalStateException("stored session " + sessionId + " is invalid", invalidField);
    }
  }

  private static void requireHoldId(String holdId) {
    Objects.requireNonNull(holdId, "hold id");
    // UUID.fromString accepts short forms such as "1-1-1-1-1"; comparing the round trip
    // accepts only the canonical 36-character form that Module 13 writes.
    if (!UUID.fromString(holdId).toString().equals(holdId)) {
      throw new IllegalArgumentException("hold id must be a canonical lower-case UUID: " + holdId);
    }
  }
}
