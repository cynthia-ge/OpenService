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
