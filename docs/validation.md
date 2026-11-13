# Java 27 validation — 13 November 2026

`JAVA_HOME=<Temurin 27.0.1.0.2+1> ./mvnw clean verify` passes **268 JUnit tests**, with zero failures, errors or skipped tests. Spring Boot 4.1.2 and Maven 3.9.16 are pinned. Exact JaCoCo totals: **1125/1125 lines, 616/616 branch outcomes, 89/89 methods**.

| Production source | Lines | Branch outcomes | Methods |
|---|---:|---:|---:|
| `Application.java` | 8/8 | 8/8 | 2/2 |
| `Passwords.java` | 33/33 | 28/28 | 5/5 |
| `Service.java` | 349/349 | 212/212 | 26/26 |
| `Setup.java` | 19/19 | 4/4 | 1/1 |
| `Store.java` | 89/89 | 52/52 | 10/10 |
| `Values.java` | 15/15 | 4/4 | 8/8 |
| `WebController.java` | 612/612 | 308/308 | 37/37 |

Every production Java class is instrumented, including startup and credential setup. Maven and the skill's exact-count gate require a ratio of 1.0 for lines, branches and methods, without exclusions. Templates/CSS are exercised through rendered pages and Chromium; Java coverage does not measure visual correctness or Terraform.

The same 113 baseline HTTP contracts and the expanded workflow acceptance suite run on SQLite and disposable PostgreSQL 17. Store/setup/password and controller boundary tests exercise configuration, legacy hashes, CLI preservation of private credentials, request bounds and database-close failures. Chromium verifies onboarding, role restrictions, incidents, notes, attachments, migration preview and desktop/laptop/mobile layouts. No acceptance tests are skipped.

The Java 27 Docker image builds, starts as UID 10001 against a fresh PostgreSQL database, answers readiness, creates a record and retains it across application-container replacement. Task-owned containers, network and volume are removed afterward. Existing demo data and cloud resources were not modified. Terraform is unchanged; its earlier validation results remain historical.

Run `sh scripts/install-browser.sh` once, start Docker, set `JAVA_HOME` to Java 27, then `sh scripts/verify.sh`. Reports: `target/site/jacoco/jacoco.xml`, `target/surefire-reports/` and `target/browser-artifacts/`. `clean` removes stale coverage. The [iteration log](iteration-log.md) records QA scope and findings. These checks establish the documented subset; they do not prove vendor-instance conformance, production load capacity or enterprise readiness.
