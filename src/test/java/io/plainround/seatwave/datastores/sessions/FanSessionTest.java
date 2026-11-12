package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The session value: every invalid field is refused, and the stored form round-trips. */
class FanSessionTest {

  private static final String SESSION_ID = "Xq3vN1rP8sTt0YwZ4aBcDe";
  private static final Instant SIGNED_IN = Instant.parse("2026-11-20T09:00:00Z");
  private static final String HOLD = "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a01";
  // Module 13's limit is four holds; the stored value is budgeted for the longest of everything.
  private static final List<String> FULL_BASKET =
      List.of(
          "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a01",
          "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a02",
          "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a03",
          "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a04");
  private static final String LONGEST_FAN_ID = "f".repeat(64);
  private static final int MOST_STORED_BYTES = 251;
  private static final Instant LAST_MILLISECOND_OF_13_DIGITS =
      Instant.ofEpochMilli(9_999_999_999_999L);

  @Test
  void storedValueRoundTripsEveryField() {
    FanSession session =
        new FanSession(
            SESSION_ID, "fan-42", "ios", List.of(HOLD), SIGNED_IN, SIGNED_IN.plusSeconds(60));

    FanSession read = FanSession.fromStoredValue(SESSION_ID, session.toStoredValue());

    assertThat(read).isEqualTo(session);
  }

  @Test
  void storedValueOfTheLargestSessionIsAtMost251Bytes() {
    FanSession largest =
        new FanSession(
            SESSION_ID,
            LONGEST_FAN_ID,
            "android",
            FULL_BASKET,
            LAST_MILLISECOND_OF_13_DIGITS,
            LAST_MILLISECOND_OF_13_DIGITS);

    int storedBytes = largest.toStoredValue().getBytes(StandardCharsets.UTF_8).length;

    assertThat(storedBytes).isEqualTo(MOST_STORED_BYTES);
  }

  @Test
  void rejectsASessionIdThatIsNot22UrlSafeCharacters() {
    assertThatThrownBy(() -> session("Xq3vN1rP8sTt0YwZ4aBcD:", "fan-42", "web", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sessionId");
  }

  @Test
  void rejectsAFanIdWithASeparatorInIt() {
    assertThatThrownBy(() -> session(SESSION_ID, "fan|42", "web", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fanId");
  }

  @Test
  void rejectsADeviceNotOnTheAllowList() {
    assertThatThrownBy(() -> session(SESSION_ID, "fan-42", "tv", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("device");
  }

  @Test
  void rejectsAFifthHoldInTheBasket() {
    List<String> fiveHolds =
        List.of(
            FULL_BASKET.get(0),
            FULL_BASKET.get(1),
            FULL_BASKET.get(2),
            FULL_BASKET.get(3),
            "0b6f2c1e-6a51-4d8e-9a43-5d7c2f8e1a05");

    assertThatThrownBy(() -> session(SESSION_ID, "fan-42", "web", fiveHolds))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most 4");
  }

  @Test
  void rejectsAHoldIdInAShortForm() {
    assertThatThrownBy(() -> session(SESSION_ID, "fan-42", "web", List.of("1-1-1-1-1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical");
  }

  @Test
  void rejectsLastSeenBeforeSignIn() {
    assertThatThrownBy(() ->
            new FanSession(
                SESSION_ID, "fan-42", "web", List.of(), SIGNED_IN, SIGNED_IN.minusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lastSeenAt");
  }

  @Test
  void copiesTheBasketSoTheCallerCannotChangeIt() {
    List<String> basket = new ArrayList<>(List.of(HOLD));
    FanSession session = session(SESSION_ID, "fan-42", "web", basket);

    basket.clear();

    assertThat(session.basketHoldIds()).containsExactly(HOLD);
  }

  @Test
  void refusesAStoredValueOfAnotherFormatVersion() {
    assertThatThrownBy(() ->
            FanSession.fromStoredValue(SESSION_ID, "v2|fan-42|web||1795165200000|1795165200000"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unknown format");
  }

  @Test
  void refusesAStoredValueWithAnInvalidField() {
    assertThatThrownBy(() ->
            FanSession.fromStoredValue(SESSION_ID, "v1|fan-42|tv||1795165200000|1795165200000"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is invalid")
        .hasCauseInstanceOf(IllegalArgumentException.class);
  }

  private static FanSession session(
      String sessionId, String fanId, String device, List<String> basket) {
    return new FanSession(sessionId, fanId, device, basket, SIGNED_IN, SIGNED_IN);
  }
}
