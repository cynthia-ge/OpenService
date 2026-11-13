# Latest platform verification

Cycle 9: 314 tests; 1562/1562 lines, 838/838 branch outcomes, 113/113 methods; zero failures/errors/skips. Real SQLite/PostgreSQL/Chromium acceptance and the exact per-file gate passed. See [platform cycle evidence](platform-foundations.md). The following earlier measurements are historical.

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
