# OpenService development and QA sequence

Requested: ten sequential `open-service` feature iterations, each followed by `qa-qc` review and repairs until a clean round. Local commits only; no push or deployment. Existing work in the other seven application repositories and the skills repository is outside this run.

## Preparation: Java baseline and QA

Fixed QA base: `1372d26` (the starting HEAD). Scope: the existing uncommitted Java rewrite, its tests, build/container configuration and documentation, including preserved attachment-read and laptop-sidebar work. The initial working tree was backed up before editing. Record this reviewed prerequisite as its own baseline commit so the ten subsequent capability commits remain independently reviewable.

Toolchain verified 13 November 2026: Java 27 is the latest GA feature release, and Spring Boot 4.1.2 supports Java 17–27. Local tests use Temurin 27.0.1.0.2+1; Docker images are pinned by digest and CI selects the same Temurin release. Sources: [Java releases](https://www.oracle.com/java/technologies/downloads/), [Temurin release](https://github.com/adoptium/temurin27-binaries/releases/tag/jdk-27.0.1.0.2%2B1), [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html).

- Baseline: 240 tests pass on Java 25, then on Java 27; 100% lines/methods, incomplete branch coverage.
- QA round 1: two confirmed input-validation defects. A repeated Table API parameter returned 200 instead of 400; an `application/json-invalid` media type was accepted with 201 instead of 415. Both new HTTP regressions fail before repair. Fixes reject repeated API parameters and parse JSON media types exactly, including structured `+json` types.
- Additional validation exercises request-size bounds without Content-Length, empty authorization with a valid session, HEAD, malformed Basic credentials, null filenames, unexpected service failures, database-close failures, malformed password hashes and configuration/CLI boundaries.
- QA round 2: no new or reopened findings and no repairs after reviewing the accumulated migration and request-validation fixes. 248 tests pass; 1033/1033 lines, 560/560 branches and 88/88 methods; the exact-count gate passes all seven production files. Java 27 Docker build and PostgreSQL restart-persistence smoke checks pass. Request handling is separated from connection ownership, retaining try-with-resources suppression/closure behavior and avoiding duplicate compiler-generated null-resource branches. No coverage exclusions were added.

## Candidate sequence

These are bounded planned slices, not claims of completed capabilities. Each iteration records its own fixed QA base, contract, acceptance, exact coverage and clean review before committing.

| Iteration | Gap | Observable capability | Evidence |
|---|---|---|---|
| 1 | OS-016 | Binary attachment upload with persistent bytes/media type and metadata response | Australia Attachment API POST `/file` |
| 2 | OS-017 | Authorized attachment deletion with audit and empty 204 response | Australia Attachment API DELETE resource |
| 3 | OS-018 | Multipart attachment upload with required form fields | Australia Attachment API POST `/upload` |
| 4 | OS-019 | Versioned Table API routes using the existing bounded CRUD contract | Australia Table API URL formats |
| 5 | OS-020 | Empty/not-empty encoded query predicates shared by Table/Aggregate | Australia operator reference |
| 6 | OS-021 | Literal ENDSWITH filtering shared by Table/Aggregate | Australia operator reference |
| 7 | OS-022 | Literal NOT LIKE filtering shared by Table/Aggregate | Australia operator reference |
| 8 | OS-023 | Table pagination-link suppression flag | Australia Table API `sysparm_suppress_pagination_header` |
| 9 | OS-024 | Table reads without a total-count query | Australia Table API `sysparm_no_count` |
| 10 | OS-025 | Attachment metadata ordering before pagination | Australia Attachment API encoded ordering |

Primary sources, retrieved 13 November 2026 from the Australia release branch: [Attachment API](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/api-reference/rest-apis/c_AttachmentAPI.md), [Table API](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/api-reference/rest-apis/c_TableAPI.md), [encoded-query operators](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-user-interface/r_OpAvailableFiltersQueries.md). Tests are original local acceptance tests, not licensed-instance conformance captures. Broader SSO/ACLs, schema migration management, native scripting and numeric aggregates remain separate candidates.

## Cycle 1: OS-016 — Binary attachment upload

Fixed QA base: `1475a4ae649fca7ea474180d86c9802ae5f19218`; branch `main`, clean starting tree. Blueprint A1/A4(a). Australia Attachment API POST /file (primary source linked above): required parent/filename query fields, raw file body, 201 metadata and Location. This route was previously GET-only and the new positive HTTP regression failed with 405 before implementation.

Acceptance passed on SQLite and PostgreSQL: agent/admin uploads, reader/anonymous rejection, session CSRF, exact binary/media-type round-trip, empty and 5 MiB files, oversized rejection, invalid/repeated/unknown/blank parameters, missing/wrong-table parents, safe filenames, atomic validation, unchanged parent revisions, audit events, and additive legacy-schema upgrade preserving bytes. Form-encoded bytes remain opaque; multipart forms are explicitly rejected on the raw endpoint.

QA round 1: two confirmed issues fixed: download Content-Type gained an unwanted charset, and servlet form parameter parsing could consume raw form-encoded bytes. Round 2: a real multipart-to-raw regression returned 201 instead of 415; fixed explicit rejection to prevent silently consuming the body. Round 3: complete accumulated diff review found no new/reopened defects, made no repairs, and all required checks pass. Unresolved findings: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 256 tests pass, no skipped tests. Exact feature gate: `Store.java`: 89/89 lines, 52/52 branches, 10/10 methods; `WebController.java`: 578/578 lines, 286/286 branches, 36/36 methods. Whole Java application: 1081/1081 lines, 586/586 branch outcomes, 88/88 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/Store.java`, `src/main/java/ge/cynthia/openservice/WebController.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

No multipart upload, attachment updates, antivirus or granular record ACL parity is claimed in this slice. Schema upgrade requires one application instance; legacy/browser uploads retain the octet-stream default. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.

## Cycle 2: OS-017 — Attachment deletion

Fixed QA base: `696b47c250a7affb610ad9daf000d550d5c89b82`; branch `main`, clean starting tree. Blueprint A1/A4(a). Australia Attachment API DELETE resource: an empty 204 response and 404 for missing attachments. The route previously rejected DELETE with 405; the new regression failed before implementation. OpenService restricts deletion to local administrators.

Acceptance passed on both databases: administrator session with CSRF deletes metadata and bytes, returns an empty 204, preserves the parent/revision and other attachments, and records actor, ID and filename in audit history. Missing/repeated deletion returns 404; unauthenticated, reader, agent, missing-CSRF, query-parameter and wrong-route cases are rejected.

QA round 1: reviewed the complete diff, authorization ordering, transaction boundaries, parent/attachment isolation, response body, tests and docs. No new or reopened findings, no repairs, no unresolved issues; all required checks pass.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 258 tests pass, no skipped tests. Exact feature gate: `WebController.java`: 589/589 lines, 292/292 branches, 36/36 methods. Whole Java application: 1092/1092 lines, 592/592 branch outcomes, 88/88 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/WebController.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

The role policy is local, not vendor ACL parity. No attachment update, multipart upload or antivirus is added in this slice. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.
