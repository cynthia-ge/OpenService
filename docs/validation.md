# Connected migration verification — 14 November 2026

Final `JAVA_HOME=<Temurin 27.0.1.0.2+1> sh scripts/verify.sh`: **441 tests**, zero failures, errors or skips. Whole-project JaCoCo: **3229/3229 executable lines, 1743/1743 branch outcomes, 196/196 methods** across all **29 production Java files**. The skill's exact per-file gate also passed every file. Chromium/V8 coverage, converted with pinned `v8-to-istanbul` 9.3.0: **75/75 lines/statements, 49/49 branch outcomes, 12/12 functions** for `migrations.js`; the skill's Istanbul gate passed too. Instrumentation, thresholds and exclusions were not weakened.

The full gate requires Java 27, Docker, Chromium and Node.js 24 (the latter only for development coverage conversion). Reports are `target/site/jacoco/jacoco.xml`, `target/browser-coverage.json`, `target/migration-browser-v8.json` and `target/surefire-reports/`. `clean` removes old reports, and the browser converter rejects a script/source mismatch. Templates are exercised through browser behavior; their markup is not part of Java executable-line counts.

SQLite and PostgreSQL acceptance covers direct synthetic source HTTP transport, allowlisted origins, OAuth failures and redirects, malformed/oversized pages, strict source IDs, reference values, interrupted/resumed keyset fetches, row/JSON/binary limits, atomic preview/apply, schema/value translation, disabled accounts, unmapped roles, membership scope, private records, attachments, private history archives, local collisions, source changes, missing records, stale jobs and cutover verification. Chromium exercises create, errors, pause/resume, double-click protection, preview rollback, apply, sync, review confirmation and saved-job selection. An authorized live ServiceNow instance has not been tested.

Final Docker image `openservice:migration-20261114` (`sha256:1e33593275fe32f0f10c9a66b7acc1ed41445619f9a398751a415f92eb157768`) built successfully. Disposable-container checks verified health, the authenticated migration wizard, anonymous API denial, UID 10001 and job persistence across a restart. All seven required license/provenance files are packaged under `META-INF/openservice`; the runtime inventory exactly matches the image's 46 nested runtime/repackaging JARs. The harness initially retained Docker's old ephemeral host port after restart, then assumed the wrong META-INF JAR prefix; correcting those harness assumptions produced the successful result. No application workaround was needed for either harness failure. Test containers were removed.

QA/QC scope: fixed base `0f53ddf` plus all task changes, including new files. The repair round addressed stale-snapshot overwrite risk, staged-reference boundaries, actionable browser error details, repeatable legacy-upgrade table creation, malformed source IDs/metadata and attachment media-type bounds. The first full run exposed the upgrade regression and was stopped after reproduction; subsequent full runs passed, including the final counters above. A complete final review found no new or reopened defects within this scope. Remaining capability and operational limits are explicit in [migration.md](migration.md) and MIGR-01–05 in [the roadmap](roadmap.md); passing tests does not establish complete ServiceNow parity or enterprise readiness.

Publication checks preserve wrapper headers, add the Apache license/NOTICE and Bouncy Castle notice, retain dependency license declarations, document fixture provenance limits and state non-affiliation. Current files and 624 reachable historical blobs were scanned for the listed common credential patterns without matches. This is not an exhaustive secret detector or legal clean-room certification. The final staged diff and all task-created commit messages must pass whitespace and authorship checks before the requested push.

# Container recovery verification — 14 November 2026

The final Java source was built into local Docker image `openservice:parity-20261114` (`sha256:7c0fe6a929badc5c4889dd0322a5d8bfea3cff190c2d14dbad591ef38421491c`). `bash scripts/restore-drill.sh openservice:parity-20261114` passed: source application/database stopped, custom-format dump restored to a different fresh PostgreSQL 17 volume, and a fresh application ran as UID 10001. Configuration, record, security-event and numeric-report snapshots matched; binary attachments matched byte-for-byte; restored account login and private-record denial passed. The final drill took 9 seconds locally, which is not a production recovery target.

Bash syntax validation and the missing-image failure path passed. Successful and failed runs left no task-owned containers, volumes or networks. The script uses pipeline failure propagation, immutable image IDs during each run and generated disposable credentials. This operational check supplements the 405-test Java suite below; it does not establish high availability or enterprise adoption readiness.

# Typed analytics verification — 14 November 2026

Final `JAVA_HOME=<Temurin 27.0.1.0.2+1> ./mvnw clean verify`: **405 tests**, zero failures/errors/skips; whole-project **2427/2427 lines, 1298/1298 branch outcomes, 157/157 methods**. The exact-count skill gate passed all 22 production Java source files. No coverage exclusions or threshold changes were introduced. Templates are verified by Chromium journeys; Java coverage does not measure template or shell-script lines.

The analytics regression exercises all 10,001 records on SQLite and PostgreSQL, exact decimals, negative/blank values, grouping/filtering, empty sets and private-record exclusion. The browser journey follows the workspace link, selects a numeric field and verifies all displayed statistics. Full prior application, configuration, migration and account acceptance also passed. The first browser check found an ambiguous select label; explicit label associations corrected it, and both the focused browser check and final suite passed.

# Reusable configuration verification — 14 November 2026

`JAVA_HOME=<Temurin 27.0.1.0.2+1> ./mvnw clean verify` passed **403 tests**, zero failures/errors/skips. Whole-project exact JaCoCo: **2359/2359 lines, 1262/1262 branch outcomes, 149/149 methods**. The skill's exact per-file gate passed every production Java source, without exclusions or threshold changes.

SQLite and disposable PostgreSQL acceptance cover package preview/install/export, invalid schemas/values, persisted-data compatibility, failed-package rollback, concurrent revision conflicts, assignment membership, inaccessible references, protected deletion, required state fields and role-gated transitions. The checked-in service operations example is exercised directly. Reopening storage preserves definitions and records. Chromium covers configuration preview/install and typed form rejection/correction. A real PostgreSQL lock test verifies that a queued write is rejected after account access is revoked.

The existing Java 27 and Spring Boot 4.1.2 pins were rechecked on 2026-11-14 against [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [Temurin release information](https://adoptium.net/news). No stack upgrade was needed. These are application acceptance results, not licensed ServiceNow conformance, load/failover evidence or completed adoption milestones. Historical validation follows.

# Latest platform verification

Cycle 20: 353 tests; 2075/2075 lines, 1104/1104 branch outcomes, 137/137 methods; zero failures/errors/skips. Real SQLite/PostgreSQL/Chromium acceptance and the exact per-file gate passed. See [platform cycle evidence](platform-foundations.md). The following earlier measurements are historical.

# Java 27 validation — 13 November 2026

`JAVA_HOME=<Temurin 27.0.1.0.2+1> ./mvnw clean verify` passes **278 JUnit tests**, with zero failures, errors or skipped tests. Spring Boot 4.1.2 and Maven 3.9.16 are pinned. Exact JaCoCo totals: **1150/1150 lines, 644/644 branch outcomes, 90/90 methods**.

| Production source | Lines | Branch outcomes | Methods |
|---|---:|---:|---:|
| `Application.java` | 8/8 | 8/8 | 2/2 |
| `Passwords.java` | 33/33 | 28/28 | 5/5 |
| `Service.java` | 362/362 | 226/226 | 27/27 |
| `Setup.java` | 19/19 | 4/4 | 1/1 |
| `Store.java` | 89/89 | 52/52 | 10/10 |
| `Values.java` | 15/15 | 4/4 | 8/8 |
| `WebController.java` | 624/624 | 322/322 | 37/37 |

The recorded measurements describe implemented code and the historical test run. They do not measure completion of the invitation, identity, collaboration or operational requirements in the [adoption roadmap](roadmap.md). Milestone acceptance/security/recovery/capacity evidence is tracked separately; M1–M3 are not met by these coverage results. This roadmap audit changed documentation only and did not rerun the application suite.

Every production Java class is instrumented, including startup and credential setup. Maven and the skill's exact-count gate require a ratio of 1.0 for lines, branches and methods, without exclusions. Templates/CSS are exercised through rendered pages and Chromium; Java coverage does not measure visual correctness or Terraform.

The 113 HTTP regression contracts and the expanded workflow acceptance suite run on SQLite and disposable PostgreSQL 17. Store/setup/password and controller boundary tests exercise configuration, legacy hashes, CLI preservation of private credentials, request bounds and database-close failures. Chromium verifies onboarding, role restrictions, incidents, notes, attachments, migration preview and desktop/laptop/mobile layouts. No acceptance tests are skipped.

The Java 27 Docker image builds, starts as UID 10001 against a fresh PostgreSQL database, answers readiness, creates a record and retains it across application-container replacement. Task-owned containers, network and volume are removed afterward. Existing demo data and cloud resources were not modified. Terraform is unchanged; its earlier validation results remain historical.

Run `sh scripts/install-browser.sh` once, start Docker, set `JAVA_HOME` to Java 27, then `sh scripts/verify.sh`. Reports: `target/site/jacoco/jacoco.xml`, `target/surefire-reports/` and `target/browser-artifacts/`. `clean` removes stale coverage. The [iteration log](iteration-log.md) records QA scope and findings. These checks establish the documented subset; they do not prove vendor-instance conformance, production load capacity or enterprise readiness.

Final container acceptance after the ten feature slices uses the rebuilt Java 27 image and a fresh PostgreSQL volume. It verifies UID 10001, readiness, versioned record creation/read, binary content type/bytes, attachment ordering, suffix aggregates, omitted count headers, record/attachment persistence after application replacement, and audited attachment deletion. The task-owned containers, network and volume were removed. One historical regression fixture was explicitly updated from rejecting ORDERBYfile_name to asserting an empty successful result; the 113-case suite was retained, alongside new populated ordering tests.

## Paired-cycle validation — 14 November 2026

Cycle 1: 407 tests pass; exact whole-project Java coverage 2,428/2,428 lines, 1,298/1,298 branches, 157/157 methods. See [per-cycle scope, QA rounds and subsequent results](paired-cycles-2026-11-14.md).

Cycle 2: 409 passing tests; exact Java line/branch/method counts 2,431/2,431; 1,300/1,300; 158/158.

Cycle 3: 411 passing tests; exact Java line/branch/method counts 2,454/2,454; 1,316/1,316; 160/160.

Cycle 4: 415 passing tests; exact Java line/branch/method counts 2,467/2,467; 1,326/1,326; 161/161.

Cycle 5: 421 passing tests; exact Java line/branch/method counts 2,485/2,485; 1,344/1,344; 163/163.

Cycle 6: 423 passing tests; exact Java line/branch/method counts 2,514/2,514; 1,362/1,362; 166/166.

Cycle 7: 426 passing tests; exact Java line/branch/method counts 2,561/2,561; 1,380/1,380; 167/167.

Cycle 8: 428 passing tests; exact Java line/branch/method counts 2,622/2,622; 1,426/1,426; 169/169.

2026-11-14 paired cycle 9: `./mvnw clean verify` passes 428 tests, zero failures/errors/skips; exact JaCoCo LINE 2646/2646, BRANCH 1438/1438, METHOD 169/169. Explicit all-production-file coverage gate and `git diff --check` pass. Preview/apply browser journey and repeatable rollback on SQLite/PostgreSQL are covered. See [paired cycle evidence](paired-cycles-2026-11-14.md).

2026-11-14 paired cycle 10: `./mvnw clean verify` passes 431 tests, zero failures/errors/skips; exact JaCoCo LINE 2662/2662, BRANCH 1452/1452, METHOD 169/169. Explicit all-production-file coverage gate and `git diff --check` pass. SQLite/PostgreSQL acceptance verifies stale-batch rollback and fresh apply; Chromium verifies conflicting edits from another tab. All ten paired cycles are complete; see [per-cycle evidence and limits](paired-cycles-2026-11-14.md).
