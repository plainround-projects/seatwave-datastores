# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first build of Module 15's code seed (18 files), run for the first time against Redis 8.10.3
and compiled against Lettuce 7.9.0.RELEASE itself (the seed had been compiled against 6.4.2.RELEASE).

### Added

- The Gradle wrapper (9.9.1, both checksums compared), `gradle/libs.versions.toml`, lockfiles,
  Spotless with google-java-format, JaCoCo's 0.80 line floor wired into `check`, README,
  ARCHITECTURE, this changelog and the license.
- `scripts/stack.sh` (`up`, `down`, `test`, `status`; a dry run unless `--run`), in the shape of
  Module 14's; `up` runs `scripts/make-acl.sh` when the ACL file is missing.
- `RedisSessionStoreStackTest` (tagged `stack`, run by `scripts/stack.sh test`): Redis's own
  `INFO commandstats` counts exactly the listed commands for every pattern; the ACL refuses `SCAN`,
  a key outside the two prefixes, and every key to the monitoring user; a touch after sign-out is
  refused and revives nothing; a session and its index field expire together; sign out everywhere
  removes one fan's sessions only.
- Unit tests for `FanSession`, `SessionKeys`, the policy, the model's expiry, lifetime, per-fan
  limit and revival rules, both settings readers and `ServerStats`.
- `ServerStats` and the load test's Redis report: the main thread's CPU over the run, the time
  Redis spent per command, and `used_memory` per preloaded session, with a warning when Redis was
  not empty before the preload.
- `SizingCli` prints the SIMPLE sessions table beside the holds, in cores at 13,333 writes a second,
  from Module 14's cost model and from its measured CPU per write, and the metrics machines both ways.
- Logback at `WARN` (`src/main/resources/logback.xml`), so the programs' output is not buried in
  SLF4J's and Netty's start-up lines.

### Changed

- The ACL grants `HPEXPIRE` instead of `HEXPIRE`, and no longer grants `HELLO` (a Lettuce
  connection signs in with `HELLO 3 AUTH`, which needs no grant) or `PING` to the session service;
  `CLIENT SETINFO` stays, so each connection adds no `NOPERM` error to Redis's counts.
- The lab passwords and ACL file moved from `build/redis` to `.secrets/redis`: `./gradlew clean`
  deleted them while Redis and its health check still read them.
- `RedisSettings.fromEnvironment` and `LoadSettings.fromEnvironment` return every problem as a
  `Result` value instead of throwing, and the programs print them and exit with status 2; the
  timeout is capped at 10 seconds and an empty host is refused.
- The load test preloads every session with a full basket of four holds, so the measured memory is
  that of the largest stored value; a touch keeps the basket; a missing session or a refused touch
  counts as a failure; the summary prints scheduled and completed calls and exits with status 1 when
  any call did not complete; time comes from one injected clock.

### Fixed

- The fan index field expired up to a second before its session, and at once when less than a
  second of the lifetime was left: Lettuce sends a `Duration` to `HEXPIRE` in whole seconds. The
  store now sends `HPEXPIRE`, in the same milliseconds as the session key's `PX`.
- `accessPatterns --redis` signed its sessions in at a fixed instant (20 November 2026, 09:00 UTC)
  while Redis's clock is real: after 21:00 UTC that day every session would have been past its
  12-hour lifetime and the run would have failed. The patterns now sign in at the store's own time.
- The load test's schedule could send one tick fewer than planned; it now sends exactly
  rate × seconds calls of each pattern.
- `compose.yaml` said the lab's 0.2 GiB figure is in Section 7 of the module; it is in Section 6c.
