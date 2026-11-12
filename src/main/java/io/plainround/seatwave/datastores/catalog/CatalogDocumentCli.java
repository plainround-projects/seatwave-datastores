package io.plainround.seatwave.datastores.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the catalog document of one event, the shape the event page reads whole: the event, its
 * venue copied in, and its sections nested inside, from Module 13's rows. It prints Arena Night's
 * document and the sizes of an arena and a club document, which Section 7's sizing uses.
 *
 * <p>Teaching simplification: the JSON is written by hand from validated values that contain no
 * character JSON must escape; production code writes it with Jackson from a record.
 */
public final class CatalogDocumentCli {

  // Module 13's layouts: the arena has sections A to T of 1,000 seats, a club one of 200.
  private static final int ARENA_SECTIONS = 20;
  private static final int ARENA_SEATS_PER_SECTION = 1_000;
  private static final int CLUB_SEATS = 200;
  private static final char FIRST_SECTION = 'A';

  private CatalogDocumentCli() {}

  /** Entry point; prints the document of event 1 and the two sizes. */
  public static void main(String[] arguments) {
    String arenaNight = arenaDocument();
    String clubNight = clubDocument();
    System.out.println("Catalog document of event 1, as the event page reads it:");
    System.out.println(arenaNight);
    System.out.println(
        String.format(
            Locale.ROOT,
            "size: arena event %,d bytes, club event %,d bytes (UTF-8, no whitespace)",
            compactBytes(arenaNight),
            compactBytes(clubNight)));
  }

  /** Module 13's Arena Night: event 1 at venue 1, 20 sections of 1,000 seats at $80.00. */
  public static String arenaDocument() {
    List<String> sections = new ArrayList<>();
    for (int sectionIndex = 0; sectionIndex < ARENA_SECTIONS; sectionIndex++) {
      sections.add(
          section(String.valueOf((char) (FIRST_SECTION + sectionIndex)), ARENA_SEATS_PER_SECTION));
    }
    return event(1, "Arena Night", "2026-12-08T20:20:51.783Z", 1, "Arena", sections);
  }

  /** A club night as Module 14's study volume has them: one section of 200 seats. */
  public static String clubDocument() {
    return event(
        2_501,
        "Club Night",
        "2026-12-09T21:00:00Z",
        26,
        "Club 26",
        List.of(section("A", CLUB_SEATS)));
  }

  /** The document's size as stored: the indented form below, without its whitespace. */
  public static int compactBytes(String document) {
    return document
        .replaceAll("\\s+(?=([^\"]*\"[^\"]*\")*[^\"]*$)", "")
        .getBytes(StandardCharsets.UTF_8)
        .length;
  }

  private static String event(
      long eventId,
      String name,
      String startsAt,
      long venueId,
      String venueName,
      List<String> sections) {
    return """
    {
      "_id": %d,
      "name": "%s",
      "starts_at": "%s",
      "venue": { "venue_id": %d, "name": "%s" },
      "sections": [
    %s
      ],
      "version": 0
    }\
    """
        .formatted(eventId, name, startsAt, venueId, venueName, String.join(",\n", sections));
  }

  private static String section(String code, int seats) {
    return "    { \"code\": \"%s\", \"price\": \"80.00\", \"currency\": \"USD\", \"seats\": %d }"
        .formatted(code, seats);
  }
}
