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
