package io.plainround.seatwave.datastores.partitions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.IntFunction;
import java.util.zip.CRC32;

/**
 * Shows how the choice of key spreads one second of SeatWave's on-sale peak over 16 partitions of a
 * partitioned key-value store, and when one partition gets hot. Each key is hashed to a partition,
 * as DynamoDB, Cassandra and Redis Cluster all do in their own ways. JDK only.
 */
public final class HotPartitionCli {

  private static final int PARTITIONS = 16;
  // DynamoDB's documented ceiling per partition (checked 11 November 2026): 1,000 write units
  // and 3,000 read units per second; one unit is a 1 KB write or a 4 KB strongly consistent read.
  private static final int WRITES_PER_PARTITION_LIMIT = 1_000;
  private static final int READS_PER_PARTITION_LIMIT = 3_000;
  // Module 1's arena peak: 20,000 seat-map reads and 13,333 hold attempts per second.
  private static final int SEAT_MAP_READS_PER_SECOND = 20_000;
  private static final int HOLD_ATTEMPTS_PER_SECOND = 13_333;
  private static final int FANS_IN_THE_SALE = 200_000;
  private static final long SEED = 15L;
  private static final double PERCENT = 100.0;

  private HotPartitionCli() {}

  /** Entry point; prints one line per key design. */
  public static void main(String[] arguments) {
    Random random = new Random(SEED);
    String[] sessionIds = new String[FANS_IN_THE_SALE];
    for (int fan = 0; fan < FANS_IN_THE_SALE; fan++) {
      sessionIds[fan] = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
    }
    System.out.println("One second of the arena on-sale over " + PARTITIONS + " partitions");
    List<Design> designs =
        List.of(
            new Design(
                "sessions by session id (S1 reads)",
                SEAT_MAP_READS_PER_SECOND + HOLD_ATTEMPTS_PER_SECOND,
                READS_PER_PARTITION_LIMIT,
                // One live session per fan of the sale, each with a random 128-bit id.
                call -> "seatwave:session:" + sessionIds[random.nextInt(FANS_IN_THE_SALE)]),
            new Design(
                "viewer counter by event id",
                SEAT_MAP_READS_PER_SECOND,
                WRITES_PER_PARTITION_LIMIT,
                call -> "seatwave:event-viewers:1"),
            new Design(
                "viewer counter, event id + 1 of 16 suffixes",
                SEAT_MAP_READS_PER_SECOND,
                WRITES_PER_PARTITION_LIMIT,
                call -> "seatwave:event-viewers:1:" + random.nextInt(PARTITIONS)),
            new Design(
                "holds by customer id",
                HOLD_ATTEMPTS_PER_SECOND,
                WRITES_PER_PARTITION_LIMIT,
                call -> "seatwave:holds:fan-" + random.nextInt(FANS_IN_THE_SALE)),
            new Design(
                "holds by event id",
                HOLD_ATTEMPTS_PER_SECOND,
                WRITES_PER_PARTITION_LIMIT,
                call -> "seatwave:holds:event-1"));
    designs.forEach(HotPartitionCli::spread);
  }

  private static void spread(Design design) {
    int[] callsPerPartition = new int[PARTITIONS];
    for (int call = 0; call < design.callsPerSecond(); call++) {
      callsPerPartition[partitionOf(design.keyOfCall().apply(call))]++;
    }
    int hottest = 0;
    int partitionsUsed = 0;
    for (int calls : callsPerPartition) {
      hottest = Math.max(hottest, calls);
      partitionsUsed += calls > 0 ? 1 : 0;
    }
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-44s %,6d/s  partitions used %2d  hottest %,6d/s (%5.1f%%)  limit %,d/s  %s",
            design.name(),
            design.callsPerSecond(),
            partitionsUsed,
            hottest,
            PERCENT * hottest / design.callsPerSecond(),
            design.limitPerPartition(),
            hottest > design.limitPerPartition()
                ? "THROTTLED: " + (hottest - design.limitPerPartition()) + "/s refused"
                : "fits"));
  }

  // CRC32 of the key's bytes, modulo the partition count: stable across runs and machines.
  private static int partitionOf(String key) {
    CRC32 checksum = new CRC32();
    checksum.update(key.getBytes(StandardCharsets.UTF_8));
    return (int) (checksum.getValue() % PARTITIONS);
  }

  private record Design(
      String name, int callsPerSecond, int limitPerPartition, IntFunction<String> keyOfCall) {}
}
