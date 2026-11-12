package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

/** The two key families: only ids that passed their pattern can name a key. */
class SessionKeysTest {

  @Test
  void newSessionIdsAre22UrlSafeCharactersAndValid() {
    String sessionId = SessionKeys.newSessionId(new SecureRandom());

    assertThat(sessionId).hasSize(22).matches(FanSession.SESSION_ID);
    assertThat(SessionKeys.sessionKey(sessionId)).isEqualTo("seatwave:session:" + sessionId);
  }

  @Test
  void refusesASessionIdThatWouldNameAnotherKey() {
    // 22 characters, so only the character check can refuse it.
    String crafted = "*:fan-sessions:fan-42*";

    assertThatThrownBy(() -> SessionKeys.sessionKey(crafted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not a session id");
  }

  @Test
  void refusesAMissingSessionId() {
    assertThatThrownBy(() -> SessionKeys.sessionKey(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not a session id");
  }

  @Test
  void buildsTheFanIndexKeyFromAValidFanId() {
    assertThat(SessionKeys.fanIndexKey("fan-42")).isEqualTo("seatwave:fan-sessions:fan-42");
  }

  @Test
  void refusesAFanIdWithAPatternCharacter() {
    assertThatThrownBy(() -> SessionKeys.fanIndexKey("fan-*"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not a fan id");
  }

  @Test
  void refusesAMissingFanId() {
    assertThatThrownBy(() -> SessionKeys.fanIndexKey(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("not a fan id");
  }
}
