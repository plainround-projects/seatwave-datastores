package io.plainround.seatwave.datastores.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import org.junit.jupiter.api.Test;

/** Reading Redis's INFO text: the main thread's CPU, the commands, and the monitor left out. */
class ServerStatsTest {

  // The shape of real INFO output from redis:8.10.3, with Redis's CRLF line ends.
  private static final String BEFORE =
      "# CPU\r\nused_cpu_sys:1.5\r\nused_cpu_user:2.5\r\nused_cpu_sys_main_thread:1.000000\r\n"
          + "used_cpu_user_main_thread:2.000000\r\n# Commandstats\r\n"
          + "cmdstat_get:calls=10,usec=20,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
          + "cmdstat_info:calls=3,usec=90,usec_per_call=30.00,rejected_calls=0,failed_calls=0\r\n"
          + "# Memory\r\nused_memory:1000\r\nused_memory_human:1000B\r\n";
  private static final String AFTER =
      "# CPU\r\nused_cpu_sys_main_thread:2.000000\r\nused_cpu_user_main_thread:4.000000\r\n"
          + "# Commandstats\r\n"
          + "cmdstat_get:calls=110,usec=220,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
          + "cmdstat_client|setinfo:calls=2,usec=4,usec_per_call=2.00,rejected_calls=0,failed_calls=0\r\n"
          + "cmdstat_info:calls=9,usec=270,usec_per_call=30.00,rejected_calls=0,failed_calls=0\r\n"
          + "# Memory\r\nused_memory:5000\r\n"
          + "# Keyspace\r\ndb0:keys=7,expires=7,avg_ttl=1799000,subexpiry=2\r\n"
          + "db1:keys=1,expires=0,avg_ttl=0,subexpiry=0\r\n";
  private static final double FIELD_TOLERANCE = 1e-9;
  private static final long BEFORE_MEMORY_BYTES = 1_000L;
  private static final long COMMANDS_BETWEEN = 102L;
  private static final long MICROS_BETWEEN = 204L;
  private static final double THREE_SECONDS_OF_CPU = 3.0;
  private static final double TEN_SECONDS = 10.0;
  private static final long KEYS_IN_TWO_DATABASES = 8L;

  @Test
  void readsTheMainThreadCpuAndTheMemory() {
    ServerStats before = ServerStats.parse(BEFORE);

    assertThat(before.mainThreadCpuSeconds())
        .isCloseTo(THREE_SECONDS_OF_CPU, offset(FIELD_TOLERANCE));
    assertThat(before.usedMemoryBytes()).isEqualTo(BEFORE_MEMORY_BYTES);
    assertThat(before.keys()).isZero();
  }

  @Test
  void addsTheKeysOfEveryDatabase() {
    assertThat(ServerStats.parse(AFTER).keys()).isEqualTo(KEYS_IN_TWO_DATABASES);
  }

  @Test
  void countsCommandsBetweenTwoSnapshotsWithoutTheMonitorsInfo() {
    ServerStats before = ServerStats.parse(BEFORE);
    ServerStats after = ServerStats.parse(AFTER);

    assertThat(after.commandsSince(before)).isEqualTo(COMMANDS_BETWEEN);
    assertThat(after.commandMicrosSince(before)).isEqualTo(MICROS_BETWEEN);
  }

  @Test
  void describesTheBusyShareAndTheCostPerCommand() {
    ServerStats before = ServerStats.parse(BEFORE);
    ServerStats after = ServerStats.parse(AFTER);

    String line = after.describeSince(before, TEN_SECONDS);

    assertThat(line)
        .isEqualTo(
            "Redis main thread 30.0% busy over 10.0 s; 102 commands (10/s), 2.00 us each to execute");
  }

  @Test
  void refusesTextWithoutThePerThreadCpu() {
    assertThatThrownBy(() -> ServerStats.parse("# Memory\r\nused_memory:5000\r\n"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("INFO has no used_cpu_user_main_thread field");
  }
}
