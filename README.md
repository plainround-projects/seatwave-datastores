# seatwave-datastores

SeatWave's session store in Redis, designed from its access patterns: two key families, a test that proves every pattern sends exactly its listed commands and never a scan, an ACL that refuses anything else, and an open-loop load test at the estimated on-sale peak. Beside it, the JDK-only programs behind a database decision for six workloads (`docs/decision-matrix.md`): a hot-partition simulation, a wide-column model of booking history, the catalog as a document and the sizing of all six. This is the project of **Module 15, "Beyond tables: choosing a database"**, of the course System Design Fundamentals; the running case is SeatWave, a ticket-booking service.

## What it shows

- Every session access pattern (S1 to S5) is one or a few key commands; Redis's own `INFO commandstats` counts exactly the commands `SessionAccessPattern` lists, and the ACL refuses `SCAN` to the session service.
- At the estimated peak, 33,333 reads and 13,333 touches a second over one Lettuce connection, Redis's main thread was 26.6 to 33.5 percent busy over five runs and spent about 0.98 µs executing each command. From a laptop into Docker Desktop's virtual machine the read p50 was 1.10 ms, while the read p99 ranged from 4.4 to 96.5 ms with the laptop's load: the tail measured the shared laptop, not Redis.
- A session and its fan-index field expire together, in milliseconds; a touch never revives a signed-out session.
- The first stack run found a defect the JDK-only model could not show: `HEXPIRE` with a `Duration` is sent in whole seconds, so the store now sends `HPEXPIRE`.

## Prerequisites

- JDK 25 (the build's toolchain asks for 25; it was run on Eclipse Temurin 25.0.2).
- Docker with Compose v2 for the lab Redis (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1). `./gradlew check` needs no Docker.
- `openssl` and `shasum`, for the generated lab passwords and their hashes.

## Dependencies

Every version is pinned in `gradle/libs.versions.toml`, transitive versions in `gradle.lockfile`; all were checked on Maven Central on 12 November 2026, and OSV listed no vulnerability for Lettuce, result4j or Awaitility that day.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `io.lettuce:lettuce-core` | 7.9.0.RELEASE | 12 Nov 2026 | MIT | The JDK has no Redis protocol client; one Lettuce connection is thread-safe and pipelines every thread's commands. |
| `com.github.sviperll:result4j` | 1.2.0 | 12 Nov 2026 | Apache-2.0 | Expected refusals and settings problems as `Result` values. |
| `org.slf4j:slf4j-api`, `ch.qos.logback:logback-classic` (runtime) | 2.0.20, 1.6.6 | 12 Nov 2026 | MIT; EPL-1.0 or LGPL-2.1 | Lettuce and Netty log through SLF4J; Logback keeps their lines at `WARN`. |
| `org.junit:junit-bom`, `org.assertj:assertj-core`, `org.awaitility:awaitility` (test) | 6.1.3, 3.27.7, 4.3.0 | 12 Nov 2026 | EPL-2.0, Apache-2.0, Apache-2.0 | Tests, assertions, and waiting for Redis to expire a key without sleeping. |
| Spotless, google-java-format, JaCoCo (build) | 8.11.0, 2.0.0, 0.8.16 | 12 Nov 2026 | Apache-2.0, Apache-2.0, EPL-2.0 | Enforced format and a 0.80 line-coverage floor. |

The image is pinned by tag and digest in `compose.yaml`, checked on Docker Hub on 12 November 2026: `redis:8.10.3` (Docker Official Image, Debian trixie, `arm64` and `amd64`; `8.12-rc2` is a release candidate). Gradle 9.9.1 through the wrapper; its jar and distribution checksums match services.gradle.org.

## Commands and what they printed

Run on 12 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other programs running; Redis in Docker Desktop's Linux virtual machine (12 CPUs, 8 GB), so every timing from Java includes the round trip into that machine. The load test was taken under the course's shared measurement lock, with the load average beside it. These are laptop numbers, not production's.

```bash
./gradlew clean check                         # format, compile with warnings as errors, unit tests, coverage
scripts/stack.sh test --run --yes             # Redis up, the stack tests and their coverage, down -v
scripts/stack.sh up --run --yes               # for the commands below
./gradlew -q accessPatterns --args=--redis    # S1 to S5 once each, counted by Redis
./gradlew -q loadTest                         # 60 s at 33,333 reads and 13,333 touches a second
scripts/stack.sh down --run --yes             # down -v, delete the passwords, check nothing is left
./gradlew -q accessPatterns sizing hotPartitions wideColumn catalogDocument   # need no Docker
SEATWAVE_LOAD_SECONDS=10 ./gradlew -q loadTest --args=--store=model          # the harness alone
```

Every `scripts/stack.sh` action is a dry run without `--run` (a dry run needs no Docker and changes nothing), asks before it changes anything unless `--yes` is given, touches only the Compose project `seatwave-datastores`, and appends an audit line to `build/stack/audit.log`. `up` runs `scripts/make-acl.sh` when `.secrets/redis/users.acl` is missing: two throwaway passwords and an ACL file holding only their SHA-256 hashes, in a folder git ignores, readable by every account on the machine because the container's `redis` user reads them through a read-only mount. `down` deletes them with the Redis that used them. The load test reads its settings from `SEATWAVE_LOAD_READS_PER_SECOND`, `SEATWAVE_LOAD_TOUCHES_PER_SECOND`, `SEATWAVE_LOAD_SECONDS`, `SEATWAVE_LOAD_SESSIONS` and `SEATWAVE_LOAD_MAX_IN_FLIGHT`, and Redis's address from `SEATWAVE_REDIS_HOST`, `SEATWAVE_REDIS_PORT` and `SEATWAVE_REDIS_TIMEOUT_MS`; every problem is reported together and the program exits with status 2.

`./gradlew clean check` (48 tests, line coverage 97.1 percent of the code `check` gates):

```text

> Task :jacocoTestCoverageVerification
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport

BUILD SUCCESSFUL in 2s
9 actionable tasks: 9 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
exit status 0
```

`scripts/stack.sh test --run --yes` (line coverage 96.3 percent of all main code but the programs, over both runs):

```text
RedisSessionStoreStackTest > signOutEverywhereRemovesEverySessionOfTheFanAndNoOther() PASSED

RedisSessionStoreStackTest > aSessionAndItsIndexFieldExpireTogether() PASSED

RedisSessionStoreStackTest > theAclRefusesTheMonitoringUserEverySessionKey() PASSED

RedisSessionStoreStackTest > theAclRefusesAScanToTheSessionService() PASSED

RedisSessionStoreStackTest > redisCountsExactlyTheListedCommandsForEveryPattern() PASSED

RedisSessionStoreStackTest > aTouchAfterSignOutIsRefusedAndDoesNotReviveTheSession() PASSED

RedisSessionStoreStackTest > theAclRefusesTheSessionServiceAKeyOutsideItsPrefixes() PASSED

> Task :test UP-TO-DATE
> Task :jacocoTestReport UP-TO-DATE
> Task :stackCoverageReport
> Task :stackCoverage

BUILD SUCCESSFUL in 3s
8 actionable tasks: 3 executed, 5 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
 Container seatwave-datastores-redis-1  Stopping
 Container seatwave-datastores-redis-1  Stopped
 Container seatwave-datastores-redis-1  Removing
 Container seatwave-datastores-redis-1  Removed
 Volume seatwave-datastores_session-data  Removing
 Network seatwave-datastores_default  Removing
 Volume seatwave-datastores_session-data  Removed
 Network seatwave-datastores_default  Removed
nothing of seatwave-datastores is left
exit status 0
```

`./gradlew -q accessPatterns --args=--redis`:

```text
Access patterns of the session workload, run once each on the lab Redis
  S1_READ_BY_ID             33,333/s  p99  5 ms  read your own writes sent {GET=1}                              as listed
  S2_CREATE_AT_SIGN_IN         555/s  p99 10 ms  read your own writes sent {HLEN=1, HPEXPIRE=1, HSET=1, SET=1}  as listed
  S3_TOUCH_ON_ACTIVITY      13,333/s  p99 10 ms  read your own writes sent {HPEXPIRE=1, SET=1}                  as listed
  S4_SIGN_OUT_ONE_DEVICE        56/s  p99 10 ms  gone at once         sent {DEL=1, HDEL=1}                      as listed
  S5_SIGN_OUT_EVERYWHERE         1/s  p99 50 ms  gone at once         sent {DEL=4, HKEYS=1}                     as listed
  scans sent by all patterns: 0
exit status 0
```

`./gradlew -q loadTest`, on a freshly started Redis (run 3 of five, the one that started on the quietest machine, 1-minute load 4.79; the other runs are in the module's Section 8):

```text
Load test of the session store on Redis at 127.0.0.1:21500
  preloaded 200,000 sessions, each with 4 holds in its basket; stored value 192.4 bytes on average
  Redis used_memory 1.5 MiB before the preload, 93.1 MiB after: 480 bytes per session (key, value, expiry and fan index entry), 288 beyond the stored value
  S1 read    1,996,082 of 1,999,980 done (33,268/s)  failed 0  dropped 3,898 (0.19%)  p50 1.103 ms  p99 14.160 ms  max 91.531 ms
    WARNING: 3898 scheduled calls did not complete; the latencies describe only those that did
  S3 touch     798,344 of 799,980 done (13,306/s)  failed 0  dropped 1,636 (0.20%)  p50 2.208 ms  p99 24.990 ms  max 129.844 ms
    WARNING: 1636 scheduled calls did not complete; the latencies describe only those that did
  Redis main thread 33.3% busy over 60.0 s; 3,592,828 commands (59,880/s), 0.98 us each to execute
```

The ACL refuses a scan:

```text
$ docker compose -p seatwave-datastores exec redis sh -c 'redis-cli --no-auth-warning --user seatwave_sessions --pass "$(cat /etc/seatwave/app.password)" SCAN 0'
NOPERM User seatwave_sessions has no permissions to run the 'scan' command

exit status 0
```

`scripts/stack.sh down --run --yes`, then the listings by the project's label:

```text
$ docker ps -a --filter label=com.docker.compose.project=seatwave-datastores
CONTAINER ID   IMAGE     COMMAND   CREATED   STATUS    PORTS     NAMES
$ docker network ls --filter label=com.docker.compose.project=seatwave-datastores
NETWORK ID   NAME      DRIVER    SCOPE
$ docker volume ls --filter label=com.docker.compose.project=seatwave-datastores
DRIVER    VOLUME NAME
```

The programs that need no Docker print what the module quotes; their outputs are in `_tools/module-15-outputs/` of the course and in the module's Sections 7, 8 and 11.

## Ports

One port, on `127.0.0.1` only, from Module 15's range 21500 to 21599: `21500`, Redis.

## Project structure

```text
.editorconfig
.gitattributes
.gitignore
ARCHITECTURE.md
CHANGELOG.md
LICENSE
README.md
build.gradle.kts
compose.yaml
docs/decision-matrix.md
gradle.lockfile
gradle/libs.versions.toml
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradlew
gradlew.bat
scripts/make-acl.sh
scripts/stack.sh
settings-gradle.lockfile
settings.gradle.kts
src/main/java/io/plainround/seatwave/datastores/catalog/CatalogDocumentCli.java
src/main/java/io/plainround/seatwave/datastores/estimate/SizingCli.java
src/main/java/io/plainround/seatwave/datastores/history/WideColumnDemoCli.java
src/main/java/io/plainround/seatwave/datastores/partitions/HotPartitionCli.java
src/main/java/io/plainround/seatwave/datastores/sessions/AccessPatternCheckCli.java
src/main/java/io/plainround/seatwave/datastores/sessions/FanSession.java
src/main/java/io/plainround/seatwave/datastores/sessions/InMemorySessionStore.java
src/main/java/io/plainround/seatwave/datastores/sessions/RedisSessionStore.java
src/main/java/io/plainround/seatwave/datastores/sessions/RedisSettings.java
src/main/java/io/plainround/seatwave/datastores/sessions/ServerStats.java
src/main/java/io/plainround/seatwave/datastores/sessions/SessionAccessPattern.java
src/main/java/io/plainround/seatwave/datastores/sessions/SessionKeys.java
src/main/java/io/plainround/seatwave/datastores/sessions/SessionLoadTestCli.java
src/main/java/io/plainround/seatwave/datastores/sessions/SessionStore.java
src/main/resources/logback.xml
src/test/java/io/plainround/seatwave/datastores/sessions/AccessPatternTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/FanSessionTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/InMemorySessionStoreTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/PolicyTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/RedisSessionStoreStackTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/ServerStatsTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/SessionKeysTest.java
src/test/java/io/plainround/seatwave/datastores/sessions/SettingsTest.java
```

## Where things came from

The design, names and numbers are the module's (its code seed, `_tools/module-15-code/`); the build, the stack script and the stack tests follow Module 14's project, `seatwave-indexes`. Module 14's measured CPU per write is quoted in `SizingCli` with its source.

## What is simplified

- The session store is a library and two programs, not a service: no HTTP endpoint, no cookies, no token check; Module 11's token and Module 10's stateless instances are assumed.
- The per-fan limit of 10 sessions is checked with `HLEN` before `SET NX`, not atomically: two sign-ins in the same millisecond may give 11 sessions. A Lua script would make it atomic at the cost of granting `EVAL`.
- One Redis, no replica, no TLS; production would add a replica, TLS and backups of the append-only file and snapshots.
- The lab preloads 200,000 sessions, a tenth of the module's 2,000,000; the load rate is the full peak.
- `HotPartitionCli`, `WideColumnDemoCli`, `CatalogDocumentCli` and `SizingCli` are models and estimates; no wide-column, document or time-series store runs here.

## No CI on purpose

There is no `.github/` folder: the course runs every check on the author's machine and prints the output here, so a reader sees what ran and where.

## The Compose path

`compose.yaml` holds the one service, `redis`, under the project name `seatwave-datastores`, started and stopped only through `scripts/stack.sh`. Nothing runs privileged, nothing uses the host network or the Docker socket, and the only folder mounted is `.secrets/redis`, read-only; data lives in the named volume `session-data`, which `down -v` removes.

## License

MIT; see `LICENSE`.
