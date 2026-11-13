# Identity and platform implementation contract

This is the implementation companion to the [adoption roadmap](roadmap.md). The initial deployment target is one dedicated organization. A shared operational pilot remains gated on the roadmap; account-lifecycle work does not establish record isolation or ServiceNow runtime parity.

## Permission model for this tranche (ADOPT-01)

| Action/resource | Administrator | Agent | Reader |
|---|---|---|---|
| List/create/change other accounts | Allowed within documented account-admin operations | Denied | Denied |
| Change own credentials/profile | Allowed through explicitly implemented self-service routes | Same | Same |
| Revoke another user's access | Allowed with last-administrator protections | Denied | Denied |
| Assign administrator privileges | Administrator only; cannot remove the final usable administrator | Denied | Denied |
| Business records, notes, attachments, exports and record audit | Current workspace-wide reads | Current workspace-wide reads | Current workspace-wide reads |
| Business mutations | Existing admin rules | Existing agent rules | Denied |

The final two rows describe the current limitation, not the intended requester/customer policy. Requester-specific visibility, team ownership, group membership and record/field ACLs remain separate ADOPT-06 prerequisites. No public registration or real customer data is introduced by this tranche. Usernames remain case-sensitive; identities will be separate from usernames. User deletion must preserve historical attribution; deactivation is the initial offboarding contract.

## Ordered database upgrades (ADOPT-02, OS-026)

Startup now obtains a transaction-scoped PostgreSQL advisory lock before schema/bootstrap operations; SQLite connections already begin an IMMEDIATE transaction. Ordered migration entries in `os_migration` commit with schema and bootstrap data. Version 1 preserves existing attachment bytes and adds the content-type default only when needed. An already-upgraded unjournaled installation can be adopted. A future or inconsistent journal fails startup. This is an original OpenService persistence contract.

Before an upgrade, stop application traffic and take a database backup including attachment bytes. Start the new version and confirm health and migration history before restoring traffic. Concurrent starts are serialized, but rolling upgrades with old binaries serving traffic are not claimed. A failed startup transaction leaves its schema changes and journal unapplied; investigate and retry the corrected release. For a committed incompatible upgrade, restore the pre-upgrade backup into a clean database before running the prior release. Backup restoration and downtime targets still need an operational drill.

Primary evidence reviewed 13 November 2026: [PostgreSQL transaction/advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html) and [SQLite transactions](https://www.sqlite.org/lang_transaction.html). This supports the local migration mechanism, not vendor database compatibility.

## Development and QA cycles

Each numbered cycle closes a bounded slice, reviews its accumulated diff against the named starting commit until a complete round is clean, runs `JAVA_HOME=$(/usr/libexec/java_home -v 27) sh scripts/verify.sh`, and checks exact JaCoCo counters. New code remains Java 27 with Spring Boot 4.1.2, verified against the [Spring Boot requirements](https://docs.spring.io/spring-boot/system-requirements.html) and [Java release roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) on 13 November 2026. Preview Spring Boot 4.2 is not selected.

### Cycle 1 — serialized versioned upgrades

Base: `9e636aa`. Scope: `Store.java`, `Migrations.java`, migration acceptance tests and the legacy-upgrade fixture. Existing readiness documentation is retained; this companion records implementation evidence separately.

QA round 1 reproduced a schema-discovery defect: JDBC metadata without a schema filter could see an attachment column in another PostgreSQL schema and skip the required upgrade. Fixed metadata lookup to the connection's active schema. The legacy HTTP fixture now removes the migration journal when simulating a pre-migration installation; its byte-preservation assertions are retained. QA round 2 found a missing acceptance case for an already-upgraded, unjournaled installation. Added a real database test preserving its existing media type; no production repair was needed. QA round 3: full accumulated-change review found no new/reopened defects and made no repairs; no unresolved findings. Clean SQLite/PostgreSQL/Chromium build and the exact per-file gate passed. 284 tests; 1162/1162 lines, 652/652 branch outcomes, 92/92 methods; zero failures/errors/skips. Scoped files: Migrations.java 15/15 lines, 10/10 branches, 2/2 methods; Store.java 86/86 lines, 50/50 branches, 10/10 methods.

### Cycle 2 — persistent user identities

Base: `7fb0417`. ADOPT-03 / OS-027, blueprint A1/A4(e). Migration 2 backfills a unique random 32-hex `user_id` for each existing account, preserving its username, password and role. Bootstrap and both account-creation entry points issue IDs. Browser sessions now bind to the immutable ID, preventing a deleted/recreated username from inheriting an old session. Existing pre-upgrade sessions must sign in again.

`GET /api/openservice/me` returns only the authenticated account's `user_id`, `name` and local `role`; Basic and session authentication are supported, query arguments are rejected, and no credentials/hashes or other accounts are disclosed. Existing account-creation responses retain their previous shape. IDs cannot be chosen or changed through account APIs. This is a local identity API, not ServiceNow `sys_user` compatibility. Original record actor names remain preserved; memberships, profile/email verification and record-level permissions remain open.

Primary evidence retrieved 13 November 2026: [ServiceNow Australia user administration](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_CreateAUser.md) and [OWASP authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html). Vendor concepts inform the design; its unique login name and this application's immutable internal identity are distinct fields.

Acceptance includes legacy backfill, uniqueness constraints, credential preservation, repeated restart, concurrent initialization, all three roles, Basic/session equivalence, exact public fields, rejected unauthenticated/query/method cases and stale-session rejection after username reuse. QA round 1 reproduced anonymous authentication against an empty identity left by an obsolete direct database writer: a missing-session fallback matched the empty ID on both databases. The repair requires an actual session identity before looking up a user, with a retained negative regression. QA round 2 corrected stale architecture/API prose that still described unversioned schema bootstrap. Final complete review and exact counters follow the passing gate.

Cycle 2 final QA round 3: complete accumulated-change review found no new/reopened findings, made no repairs and left no confirmed issue unresolved. Clean verification and exact per-file gates passed: 288 tests; 1185/1185 lines, 662/662 branch outcomes, 93/93 methods; zero failures/errors/skips. Scoped coverage: WebController.java: 634/634 lines, 326/326 branches, 37/37 methods; Migrations.java: 26/26 lines, 16/16 branches, 3/3 methods; Service.java: 363/363 lines, 226/226 branches, 27/27 methods; Store.java: 87/87 lines, 50/50 branches, 10/10 methods.

### Cycle 3 — revoke all browser sessions

Base: `0e19f98`. ADOPT-05/08 / OS-028. Migration 3 adds a persistent authentication revision. Login binds that revision to the session; every authenticated session request checks it against the current account. `POST /api/openservice/me/sessions/revoke` accepts an empty JSON object and revokes the caller's browser sessions. The account-security page provides the same operation to every local role, including readers. Session writes require CSRF. Revocation persists across restart and takes effect on the next request; in-flight requests may finish. Basic credentials remain valid, so users can sign in again. This is not account deactivation or password recovery.

Primary references retrieved 13 November 2026: [Australia user-session administration](https://www.servicenow.com/docs/r/platform-administration/user-administration/c_ManageUserSessions.html), updated March 12, and [OWASP session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html). ServiceNow describes separate session termination and account lockout; OpenService's self-service all-browser revocation is an explicitly local workflow, not its node-specific session administration API.

Acceptance: two independent browser sessions are revoked together; unrelated credentials remain usable; restart preserves revocation; fresh login succeeds; missing CSRF and extraneous API parameters cannot revoke access; reader self-service does not permit record mutations. Real Chromium covers the navigation/button, both browser contexts, subsequent sign-in and mobile layout. QA checks cover revision binding, transaction boundaries, authorization and stale documentation.

Cycle 3 final QA round 1: complete accumulated-change review found no new/reopened findings, made no repairs and left no confirmed issue unresolved. Clean verification and exact per-file gates passed: 291 tests; 1212/1212 lines, 678/678 branch outcomes, 94/94 methods; zero failures/errors/skips. Scoped coverage: WebController.java: 656/656 lines, 340/340 branches, 37/37 methods; Migrations.java: 29/29 lines, 18/18 branches, 3/3 methods; Service.java: 365/365 lines, 226/226 branches, 28/28 methods.

### Cycle 4 — account deactivation and reactivation

Base: `0d75489`. ADOPT-05 / OS-029. Migration 4 adds active/inactive account state, preserving existing accounts as active. Administrators can open an account from the account list and change its state, or PATCH its local status endpoint. Deactivation blocks browser login, existing sessions and Basic authentication; each effective state change also increments the authentication revision, so reactivation never restores old sessions. Account IDs, credentials, records and historical attribution are retained. Idempotent repeats do not rotate sessions. In-flight requests may finish; subsequent requests enforce the new state.

All administrative state changes serialize through a transaction-scoped database lock and recheck the actor's current active administrator permission after acquiring it. At least one active administrator must remain. Independent concurrent deactivations on SQLite and PostgreSQL prove this invariant. Other acceptance covers all authentication paths, every role, restart/reactivation, invalid input, unauthorized/unknown targets, CSRF, protected UI errors and a real responsive Chromium administrator/user journey.

Primary evidence retrieved 13 November 2026: [ServiceNow Australia account state and lockout concepts](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_CreateAUser.md) and [OWASP authorization](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html). OpenService combines inactive state with denied authentication; it does not reproduce vendor user_admin, business rules, reference-picker filtering or native sys_user APIs. Role editing, security-event audit and account deletion remain separate work.

QA round 1 confirmed a fresh SQLite migration failure (`Query returns results`) when its driver performs CHECK validation during ALTER TABLE. The migration now uses JDBC `execute()` for DDL; the same fresh-install, legacy-upgrade and concurrent-start tests cover both databases. The final complete review checks all state routes, lock/transaction ordering, session invalidation, error responses and browser wiring.

Cycle 4 final QA round 2: complete accumulated-change review found no new/reopened findings, made no repairs and left no confirmed issue unresolved. Clean verification and exact per-file gates passed: 296 tests; 1275/1275 lines, 710/710 branch outcomes, 97/97 methods; zero failures/errors/skips. Scoped coverage: WebController.java: 694/694 lines, 348/348 branches, 38/38 methods; Migrations.java: 34/34 lines, 20/20 branches, 3/3 methods; Service.java: 385/385 lines, 248/248 branches, 30/30 methods.
