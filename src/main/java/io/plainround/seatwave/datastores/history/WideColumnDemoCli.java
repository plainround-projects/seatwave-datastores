package io.plainround.seatwave.datastores.history;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A JDK-only model of a wide-column store holding SeatWave's booking history at one hundredth of
 * Module 14's volume. A table is a map from partition key to a sorted map of rows (the sort key); a
 * read names one partition and a range of sort keys, and the model counts partitions and rows it
 * touched. It shows why the same bookings are written twice, once per query, and what a query on
 * the wrong table costs.
 */
public final class WideColumnDemoCli {

  // One hundredth of Module 14's study volume: 40,000,000 bookings, 5,000 events, 5,000,000
  // customers become 400,000, 50 and 50,000 (still about 8 bookings per customer).
  private static final int BOOKINGS = 400_000;
  private static final int EVENTS = 50;
  private static final int CUSTOMERS = 50_000;
  private static final Instant YEAR_START = Instant.parse("2026-01-01T00:00:00Z");
  private static final long SECONDS_IN_YEAR = Duration.ofDays(365).toSeconds();
  private static final int PAGE_SIZE = 20;
  private static final long SEED = 15L;

  private WideColumnDemoCli() {}

  /** Entry point; builds both tables and runs the three queries. */
  public static void main(String[] arguments) {
    Table byCustomer = new Table("bookings_by_customer");
    Table byEvent = new Table("bookings_by_event");
    Random random = new Random(SEED);
    Map<Long, Integer> seatsSold = new HashMap<>();
    for (int bookingNumber = 0; bookingNumber < BOOKINGS; bookingNumber++) {
      long eventId = 1 + random.nextInt(EVENTS);
      long customerId = 1 + random.nextInt(CUSTOMERS);
      Instant confirmedAt = YEAR_START.plusSeconds((long) (random.nextDouble() * SECONDS_IN_YEAR));
      String seatLabel = "A-" + seatsSold.merge(eventId, 1, Integer::sum);
      Booking booking =
          new Booking(
              new UUID(random.nextLong(), random.nextLong()),
              eventId,
              seatLabel,
              customerId,
              confirmedAt);
      // The same booking, written once per query it must serve: denormalized on purpose.
      byCustomer.put("customer#" + customerId, confirmedAt + "#" + booking.bookingId(), booking);
      byEvent.put("event#" + eventId, seatLabel, booking);
    }
    System.out.println(
        String.format(
            Locale.ROOT, "%,d bookings written to two tables (%,d rows)", BOOKINGS, 2 * BOOKINGS));
    byCustomer.describe();
    byEvent.describe();

    System.out.println("My tickets, newest first, page of " + PAGE_SIZE + " (customer 42):");
    Read history = byCustomer.readNewestFirst("customer#42", PAGE_SIZE);
    System.out.println("  on bookings_by_customer: " + history.summary());
    Read doorList = byEvent.readAll("event#1");
    System.out.println("Door list of event 1, in seat order:");
    System.out.println("  on bookings_by_event:    " + doorList.summary());
    Read wrongTable = byEvent.scanFor(booking -> booking.customerId() == 42);
    System.out.println("My tickets asked of the wrong table:");
    System.out.println("  on bookings_by_event:    " + wrongTable.summary());
  }

  /** One booking, as both tables store it (a copy of Module 13's bookings columns). */
  private record Booking(
      UUID bookingId, long eventId, String seatLabel, long customerId, Instant confirmedAt) {}

  /** What a read touched, the numbers a wide-column store's metrics would show. */
  private record Read(int partitionsTouched, int rowsRead, int rowsReturned) {
    String summary() {
      return String.format(
          Locale.ROOT,
          "%,d partition(s) touched, %,d rows read, %,d returned",
          partitionsTouched,
          rowsRead,
          rowsReturned);
    }
  }

  /** A table: partition key to rows sorted by sort key. Confined to the demo's one thread. */
  private static final class Table {
    private final String name;
    private final Map<String, NavigableMap<String, Booking>> partitions = new HashMap<>();

    Table(String name) {
      this.name = name;
    }

    void put(String partitionKey, String sortKey, Booking booking) {
      partitions.computeIfAbsent(partitionKey, ignored -> new TreeMap<>()).put(sortKey, booking);
    }

    // One partition, read from the end of its sort order: newest first, stop after a page.
    Read readNewestFirst(String partitionKey, int limit) {
      NavigableMap<String, Booking> rows = partitions.getOrDefault(partitionKey, new TreeMap<>());
      List<Booking> page = rows.descendingMap().values().stream().limit(limit).toList();
      return new Read(1, page.size(), page.size());
    }

    Read readAll(String partitionKey) {
      int rows = partitions.getOrDefault(partitionKey, new TreeMap<>()).size();
      return new Read(1, rows, rows);
    }

    // No key names the rows, so every partition and every row is read: a full scan.
    Read scanFor(java.util.function.Predicate<Booking> wanted) {
      int rowsRead = 0;
      List<Booking> found = new ArrayList<>();
      for (NavigableMap<String, Booking> rows : partitions.values()) {
        for (Booking booking : rows.values()) {
          rowsRead++;
          if (wanted.test(booking)) {
            found.add(booking);
          }
        }
      }
      return new Read(partitions.size(), rowsRead, found.size());
    }

    void describe() {
      int largest = partitions.values().stream().mapToInt(Map::size).max().orElse(0);
      System.out.println(
          String.format(
              Locale.ROOT,
              "  %-21s %,7d partitions, largest %,6d rows, average %,.1f rows",
              name,
              partitions.size(),
              largest,
              (double) BOOKINGS / partitions.size()));
    }
  }
}
