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

## Cycle 3: OS-018 — Multipart attachment upload

Fixed QA base: `23954787b20ee5743a3ec67ef0a40558c90a2eb8`; branch `main`, clean starting tree. Blueprint A1/A4(a). Australia Attachment API POST /upload: parent fields in the multipart body, file-part Content-Type, 201 metadata and Location. The official example uses uploadFile. The positive regression failed with 405 before implementation; a second regression using the documented part name failed with 400 before the QA repair.

Acceptance passed on SQLite/PostgreSQL for agent/admin and session-CSRF uploads, literal bytes/media type, empty and maximum-size files, metadata/Location, audit persistence, oversized files, forbidden roles, missing/duplicate/extra fields, missing/multiple/wrong file parts, blank/null filenames, absent part media types, query-string rejection and invalid parent/table pairing. Binary and multipart APIs use the same validation and persistence method.

QA round 1: one confirmed contract mismatch fixed: the initial implementation required file rather than the documented uploadFile part name. Corrected production code and positive/negative acceptance cases; the documented-name regression now passes. Round 2: reviewed the complete accumulated diff, shared upload validation, request/body boundaries, rollback, permissions and docs. No new/reopened defects or repairs; all required checks pass. Unresolved: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 262 tests pass, no skipped tests. Exact feature gate: `WebController.java`: 609/609 lines, 304/304 branches, 37/37 methods. Whole Java application: 1112/1112 lines, 604/604 branch outcomes, 89/89 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/WebController.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

Exactly one uploadFile part and the existing local metadata subset are supported. Attachment updates, malware scanning and native field/record ACL parity remain outside this slice. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.

## Cycle 4: OS-019 — Versioned Table API routes

Fixed QA base: `559c62991f5258e848e0d04b8bcfb7a15a8c11c9`; branch `main`, clean starting tree. Blueprint A4(a). Australia Table API documents /api/now/{api_version}/table paths and examples v1/v2. Its collection contract distinguishes v1 empty results (404) from v2 (200 with an empty result array). The new positive regression returned 404 on creation before implementation.

Both database suites verify v1/v2 creation, shared unversioned records, field projection, ETags, update/PUT, stale-revision conflict, role restrictions, deletion, invalid/repeated parameters, empty/filter-empty/out-of-range pages, unsupported versions and version-preserving Location/Link headers.

QA round 1: reviewed both route patterns, unchanged captures/callers, empty-result semantics against the primary reference, authorization/CSRF dispatch, pagination and revision behavior, tests and docs. No new/reopened defects or repairs; all required checks pass. Unresolved: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 264 tests pass, no skipped tests. Exact feature gate: `WebController.java`: 612/612 lines, 308/308 branches, 37/37 methods. Whole Java application: 1115/1115 lines, 608/608 branch outcomes, 89/89 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/WebController.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

Only the existing bounded Table API subset is versioned. Other historical vendor-version differences, versioned Attachment/Aggregate APIs and complete vendor parity are not claimed. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.

## Cycle 5: OS-020 — Empty and nonempty encoded-query predicates

Fixed QA base: `cc24f6f4796d6ebc4433f0aadbd81c7a6b6e3a28`; branch `main`, clean starting tree. Blueprint A4(d). Australia operator reference defines ISEMPTY and ISNOTEMPTY as operand-free absence/presence tests. The first HTTP regression failed with unsupported-query 400 before implementation. OpenService specifies missing JSON keys, JSON null and empty strings as empty; whitespace is a value.

SQLite/PostgreSQL acceptance covers missing/null/empty/whitespace/nonempty records, both operators, AND/order composition, filtering before pagination, full-selection aggregate counts, contradictory predicates and rejection of operands, unknown fields and OR syntax through both APIs.

QA round 1: reviewed parser alternatives, SQL null/text semantics on both database expressions, whitelisted columns, bound values in combined predicates, pagination/count callers, regression assertions and docs. No new/reopened findings or repairs; required checks pass. Unresolved: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 266 tests pass, no skipped tests. Exact feature gate: `Service.java`: 346/346 lines, 212/212 branches, 26/26 methods. Whole Java application: 1122/1122 lines, 616/616 branch outcomes, 89/89 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/Service.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

Stored-value predicates only; no display-value resolution, reference traversal, OR grammar or native scripting is added. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.

## Cycle 6: OS-021 — Literal ENDSWITH filtering

Fixed QA base: `35c89df8656ac61c6ce9bf861598b237fcfb8088`; branch `main`, clean starting tree. Blueprint A4(d). Australia string operator reference defines ENDSWITH as matching a stored suffix. The new real HTTP regression failed with unsupported-query 400 before implementation. OpenService reuses bound, escaped LIKE-family SQL rather than interpreting user wildcard characters.

SQLite/PostgreSQL Table and Aggregate cases verify ordinary suffixes versus prefixes/middle text, percent/underscore/backslash literals, injection-shaped quote strings, Unicode, empty operands, null exclusion, system fields, AND/order composition, pagination, counts and invalid fields/operator rejection.

QA round 1: reviewed operator parsing, wildcard placement, escaping and binding, empty/null handling, shared Table/Aggregate callers, tests and documentation. No new/reopened findings or repairs; required checks pass. Unresolved: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 268 tests pass, no skipped tests. Exact feature gate: `Service.java`: 349/349 lines, 212/212 branches, 26/26 methods. Whole Java application: 1125/1125 lines, 616/616 branch outcomes, 89/89 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/Service.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

Existing database LIKE comparison policy is retained: SQLite default ASCII-insensitive behavior differs from PostgreSQL case-sensitive behavior. No vendor collation, reference/display-value or full encoded-query parity is claimed. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.

## Cycle 7: OS-022 — Literal NOT LIKE filtering

Fixed QA base: `d8549cf71c3258549367e9bd7214c3d75f13ff6a`; branch `main`, clean starting tree. Blueprint A4(d). Australia operator reference spells the negative substring operator NOT LIKE with a space. The positive HTTP regression failed with unsupported-query 400 before implementation. OpenService binds and escapes a literal substring, using SQL NOT LIKE.

Both database suites verify exclusion of ordinary, wildcard/backslash, quote/injection-shaped and Unicode substrings; absent/empty operands; empty stored strings and null exclusion; AND with ISNOTEMPTY; ordering/pagination; matching full-selection aggregate counts; and unknown fields/unsupported spelling.

QA round 1: reviewed parsing versus NOT IN and LIKE, bound SQL/wildcard escaping, three-valued null semantics, combined predicates, count/pagination callers, tests and docs. No new/reopened findings or repairs; all required checks pass. Unresolved: none.

Validation: `JAVA_HOME=<Java 27> ./mvnw -q clean verify`; 270 tests pass, no skipped tests. Exact feature gate: `Service.java`: 352/352 lines, 214/214 branches, 26/26 methods. Whole Java application: 1128/1128 lines, 618/618 branch outcomes, 89/89 methods. Reviewed scope: `src/main/java/ge/cynthia/openservice/Service.java`, acceptance tests and supporting documentation. Production scope is reconciled against the final diff.

Existing database case-comparison differences and stored-value-only scope remain documented. No display-value resolution or complete vendor encoded-query parity is claimed. Remaining candidates: see the sequence above; only this slice is implemented in this cycle.
