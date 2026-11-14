# OpenService and QA/QC paired cycles — 14 November 2026

Starting checkout: `d208efc`, `main`, clean. Ten sequential bounded invocations authorized. No push. Tests use synthetic accounts and real HTTP/SQLite/PostgreSQL/Chromium; no licensed ServiceNow instance conformance is claimed.

Stack reverified 2026-11-14: Java 27.0.1.0.2+1, Spring Boot 4.1.2, Maven 3.9.16, JaCoCo 0.8.16. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html) supports Java 27; [Temurin support](https://adoptium.net/support) lists the installed maintenance release. Preview Boot 4.2 is not selected.

Every cycle runs `JAVA_HOME=/Users/david/Library/Java/JavaVirtualMachines/jdk-27.0.1.0.2+1/Contents/Home ./mvnw clean verify`, then the skill's `scripts/check_coverage.py --report target/site/jacoco/jacoco.xml` with every `src/main/java/**/*.java` explicitly supplied as `--file`. Source scope is frozen before measurement. Results below refer to that cycle, not to subsequent changes.

## Cycle 1 — initial-state authorization (OS-Q10-01)

QA base `d208efc`. Areas OS-PARITY-01/02/08; blueprint A1/A4(e). Creation omitted the state-entry role policy enforced by updates. A regression failed before repair with expected 403, actual 201. Creation now checks the initial state's configured roles, including when the caller omits state. Denied requests leave no record or record event; authorized creation remains available. Administrator migration imports retain their documented separate policy. No UI contract changes.

Discovery: retrieved [Australia contextual security](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-security/access-control/r_ContextualSecurity.md), [current release highlights](https://www.servicenow.com/docs/r/release-notes/rn-summary-highlights.html), [granular admin roles](https://www.servicenow.com/docs/r/platform-security/granular-admin-roles.html) and [OWASP authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html) on 2026-11-14. Australia remains the base target; current highlights/role pages resolve to Brazil. Create authorization is an established platform capability, but this state policy is a local configuration contract. New TODO OS-DISC-01 records granular operational administration; it does not change the target release or assert product licensing. Next discovery area: field ACLs and data policies.

QA round 1: reviewed the complete diff, shared create callers, transaction rollback, update/import differences and policy defaults. One reproduced creation bypass fixed; no reopened findings. Required checks pending.

QA round 2: complete accumulated diff re-reviewed against `d208efc`; no new/reopened defects, no repairs, no unresolved findings. Checks: 407 tests, zero failures/errors/skips; exact project coverage 2,428/2,428 lines, 1,298/1,298 branches, 157/157 methods across all 22 Java files. Touched Service.java: 455/455 lines, 284/284 branches, 34/34 methods. Skill exact-count gate and `git diff --check` pass. OS-Q10-01 implemented.
