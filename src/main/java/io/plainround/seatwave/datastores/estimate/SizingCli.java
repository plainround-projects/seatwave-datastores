package io.plainround.seatwave.datastores.estimate;

import io.plainround.seatwave.datastores.catalog.CatalogDocumentCli;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Module 15's sizing of the six SeatWave workloads (its Section 7), with every input named: the
 * brief's four volumes, Module 1's peak and machine, Module 14's row sizes, estimates and measured
 * CPU per write, and the assumptions marked as such. It prints estimates, not measurements of any
 * store; the columns marked "meas." apply a cost Module 14 measured. JDK only.
 */
public final class SizingCli {

  private static final double KIB = 1_024.0;
  private static final double MIB = KIB * KIB;
  private static final double GIB = MIB * KIB;
  private static final long SECONDS_PER_DAY = 86_400L;
  private static final long DAYS_PER_YEAR = 365L;
  private static final long SECONDS_PER_HOUR = 3_600L;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final double BYTES_PER_MEGABYTE = 1_000_000.0;
  private static final long ONE_MILLION = 1_000_000L;
  private static final double PERCENT = 100.0;
  private static final int MONEY_SCALE = 6;
  // Module 14's study volume: half the events are arena nights, half are club nights.
  private static final double ARENA_SHARE = 0.5;
  private static final int MOST_HOPS = 3;

  // The module brief: 2 million active sessions of 1 KB, 5,000 events, 50 million bookings
  // a year, 100,000 metric points a second.
  private static final long ACTIVE_SESSIONS = 2_000_000L;
  private static final long SESSION_BYTES = 1_024L;
  private static final long EVENTS = 5_000L;
  private static final long BOOKINGS_PER_YEAR = 50_000_000L;
  private static final long METRIC_POINTS_PER_SECOND = 100_000L;

  // Module 1's arena peak, and the reference machine's price (checked in Module 1).
  private static final long SEAT_MAP_READS_PER_SECOND = 20_000L;
  private static final long HOLD_ATTEMPTS_PER_SECOND = 13_333L;
  private static final BigDecimal MACHINE_USD_PER_HOUR = new BigDecimal("0.2016");

  // Assumptions of this module before its build; the load test (Module 15, Section 8) measured
  // what replaces them, and the module gives both.
  private static final long REDIS_KEY_OVERHEAD_BYTES = 90L;
  private static final long FAN_INDEX_FIELD_BYTES = 60L;
  private static final double REDIS_MICROS_PER_COMMAND = 5.0;
  private static final long AVERAGE_SESSION_SECONDS = 3_600L;
  private static final long COMMANDS_PER_TOUCH = 2L;
  private static final long COMMANDS_PER_SIGN_IN = 4L;
  // Module 14: 40,000,000 bookings fill 533,334 pages of 8,192 bytes.
  private static final double BOOKING_ROW_BYTES = 533_334.0 * 8_192 / 40_000_000;
  // Raw time-series point: an 8-byte timestamp and an 8-byte value. Compressed: the Gorilla
  // paper's reported average of about 1.37 bytes per point (an assumption for SeatWave's data).
  private static final double RAW_POINT_BYTES = 16.0;
  private static final double COMPRESSED_POINT_BYTES = 1.37;
  // A point as a PostgreSQL row: 24-byte header + 4-byte line pointer + series id, time and
  // value (8 bytes each), plus a B-tree entry on (series id, time) of 8 + 16 + 4 bytes.
  private static final double POSTGRES_POINT_BYTES = 24 + 4 + 3 * 8 + (8 + 16 + 4);
  // Module 14's cost model, the estimate before its build: 100 us per write plus 15 us per index
  // entry, on the reference machine's 4 vCPU.
  private static final double MODEL_MICROS_PER_WRITE = 100.0;
  private static final double MODEL_MICROS_PER_INDEX_ENTRY = 15.0;
  // Module 14's measurement (seatwave-indexes, PostgreSQL 18.6 in Docker Desktop's virtual machine
  // on a laptop, 12 November 2026): database CPU per hold insert, 143 us with the primary key only
  // (1 index entry) and 166 us with the final index set (5 entries). A laptop core is not a
  // reference vCPU, so the cores below apply a laptop cost to the reference machine.
  private static final double MEASURED_MICROS_ONE_ENTRY = 143.0;
  private static final double MEASURED_MICROS_FIVE_ENTRIES = 166.0;
  private static final double MEASURED_EXTRA_ENTRIES = 4.0;
  private static final double REFERENCE_VCPUS = 4.0;
  // A hold insert writes 5 index entries with the final set (Module 14). A session update in
  // the SIMPLE table changes expires_at, which is indexed for the sweeper, so it is not HOT and
  // writes 2: the primary key's and the expires_at index's.
  private static final int HOLD_INDEX_ENTRIES = 5;
  private static final int SESSION_UPDATE_INDEX_ENTRIES = 2;
  private static final long COMPANIONS_PER_FAN = 20L;
  private static final long CUSTOMERS = 5_000_000L;
  // Each companionship is listed by both fans, so the edge count is half the list entries.
  private static final long ENDS_PER_EDGE = 2L;

  private SizingCli() {}

  /** Entry point; prints one block per workload. */
  public static void main(String[] arguments) {
    sessions();
    sessionsInPostgres();
    catalog();
    orders();
    fanGraph();
    metrics();
  }

  private static void sessions() {
    long reads = SEAT_MAP_READS_PER_SECOND + HOLD_ATTEMPTS_PER_SECOND;
    long touches = HOLD_ATTEMPTS_PER_SECOND;
    long signIns = ACTIVE_SESSIONS / AVERAGE_SESSION_SECONDS;
    long commands = reads + touches * COMMANDS_PER_TOUCH + signIns * COMMANDS_PER_SIGN_IN;
    double memoryBytes =
        ACTIVE_SESSIONS * (SESSION_BYTES + REDIS_KEY_OVERHEAD_BYTES + FAN_INDEX_FIELD_BYTES);
    double busyShare = commands * REDIS_MICROS_PER_COMMAND / MICROS_PER_SECOND;
    BigDecimal commandsPerHour = BigDecimal.valueOf(commands * SECONDS_PER_HOUR);
    BigDecimal usdPerMillion =
        MACHINE_USD_PER_HOUR
            .multiply(BigDecimal.valueOf(ONE_MILLION))
            .divide(commandsPerHour, MONEY_SCALE, RoundingMode.HALF_UP);
    System.out.println("sessions (key-value)");
    line("reads per second (S1)", "%,d", reads);
    line("touches per second (S3)", "%,d", touches);
    line("sign-ins per second (S2, Little's Law)", "%,d", signIns);
    line("Redis commands per second at peak", "%,d", commands);
    line("memory, data + key overhead + index", "%.2f GiB", memoryBytes / GIB);
    line("command thread busy at peak", "%.0f%%", PERCENT * busyShare);
    line("read traffic out of Redis", "%.1f MB/s", reads * SESSION_BYTES / BYTES_PER_MEGABYTE);
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-44s $%s",
            "machine cost per million commands at peak",
            usdPerMillion.toPlainString()));
  }

  // SIMPLE: sessions as a PostgreSQL table, on the same 4 vCPU as the holds of the on-sale.
  private static void sessionsInPostgres() {
    double estimatedHoldMicros =
        MODEL_MICROS_PER_WRITE + HOLD_INDEX_ENTRIES * MODEL_MICROS_PER_INDEX_ENTRY;
    double estimatedSessionMicros =
        MODEL_MICROS_PER_WRITE + SESSION_UPDATE_INDEX_ENTRIES * MODEL_MICROS_PER_INDEX_ENTRY;
    double measuredMicrosPerExtraEntry =
        (MEASURED_MICROS_FIVE_ENTRIES - MEASURED_MICROS_ONE_ENTRY) / MEASURED_EXTRA_ENTRIES;
    double measuredSessionMicros =
        MEASURED_MICROS_ONE_ENTRY
            + (SESSION_UPDATE_INDEX_ENTRIES - 1) * measuredMicrosPerExtraEntry;
    System.out.println("sessions as a PostgreSQL table (SIMPLE), beside the holds");
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-44s %13s  %13s",
            "cores at 13,333 writes/s of each",
            "estimate",
            "measured CPU"));
    cores("hold inserts (5 index entries)", estimatedHoldMicros, MEASURED_MICROS_FIVE_ENTRIES);
    cores(
        "session updates, not HOT (2 index entries)",
        estimatedSessionMicros,
        measuredSessionMicros);
    cores(
        "together, on 4 vCPU",
        estimatedHoldMicros + estimatedSessionMicros,
        MEASURED_MICROS_FIVE_ENTRIES + measuredSessionMicros);
  }

  private static void cores(String label, double estimatedMicros, double measuredMicros) {
    System.out.println(
        String.format(
            Locale.ROOT,
            "  %-44s %13.2f  %13.2f",
            label,
            HOLD_ATTEMPTS_PER_SECOND * estimatedMicros / MICROS_PER_SECOND,
            HOLD_ATTEMPTS_PER_SECOND * measuredMicros / MICROS_PER_SECOND));
  }

  private static void catalog() {
    int arenaBytes = CatalogDocumentCli.compactBytes(CatalogDocumentCli.arenaDocument());
    int clubBytes = CatalogDocumentCli.compactBytes(CatalogDocumentCli.clubDocument());
    double totalBytes = EVENTS * ARENA_SHARE * arenaBytes + EVENTS * (1 - ARENA_SHARE) * clubBytes;
    System.out.println("catalog (documents)");
    line("arena / club document", "%,d / %,d bytes", arenaBytes, clubBytes);
    line("all 5,000 events", "%.1f MiB", totalBytes / MIB);
    line("reads per second at peak (one per seat map)", "%,d", SEAT_MAP_READS_PER_SECOND);
  }

  private static void orders() {
    double perDay = (double) BOOKINGS_PER_YEAR / DAYS_PER_YEAR;
    System.out.println("orders (relational: bookings)");
    line("bookings per day, average", "%,.0f", perDay);
    line("average per second", "%.2f", perDay / SECONDS_PER_DAY);
    line("bytes per row (Module 14's pages)", "%.1f", BOOKING_ROW_BYTES);
    line("table growth per day", "%.1f MiB", perDay * BOOKING_ROW_BYTES / MIB);
    line("table growth per year", "%.2f GiB", BOOKINGS_PER_YEAR * BOOKING_ROW_BYTES / GIB);
  }

  private static void fanGraph() {
    long edges = CUSTOMERS * COMPANIONS_PER_FAN / ENDS_PER_EDGE;
    System.out.println("fan graph (who attends with whom)");
    line("undirected edges", "%,d", edges);
    for (int hops = 1; hops <= MOST_HOPS; hops++) {
      line(
          "fans reached in " + hops + " hop(s), at most",
          "%,d",
          (long) Math.pow(COMPANIONS_PER_FAN, hops));
    }
  }

  private static void metrics() {
    long perDay = METRIC_POINTS_PER_SECOND * SECONDS_PER_DAY;
    System.out.println("metrics (time series)");
    line("points per day", "%,d", perDay);
    line("raw, 16 bytes a point", "%.1f GiB/day", perDay * RAW_POINT_BYTES / GIB);
    line("compressed, 1.37 bytes a point", "%.1f GiB/day", perDay * COMPRESSED_POINT_BYTES / GIB);
    line("as PostgreSQL rows with one index", "%.1f GiB/day", perDay * POSTGRES_POINT_BYTES / GIB);
    // One point is one insert with one index entry, the primary key on (series id, time).
    line(
        "PostgreSQL machines just to insert (est.)",
        "%.1f",
        METRIC_POINTS_PER_SECOND
            * (MODEL_MICROS_PER_WRITE + MODEL_MICROS_PER_INDEX_ENTRY)
            / MICROS_PER_SECOND
            / REFERENCE_VCPUS);
    line(
        "PostgreSQL machines just to insert (meas.)",
        "%.1f",
        METRIC_POINTS_PER_SECOND * MEASURED_MICROS_ONE_ENTRY / MICROS_PER_SECOND / REFERENCE_VCPUS);
  }

  private static void line(String label, String format, Object... values) {
    System.out.println(String.format(Locale.ROOT, "  %-44s " + format, prepend(label, values)));
  }

  private static Object[] prepend(String label, Object[] values) {
    Object[] all = new Object[values.length + 1];
    all[0] = label;
    System.arraycopy(values, 0, all, 1, values.length);
    return all;
  }
}
