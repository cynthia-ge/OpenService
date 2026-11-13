# OpenService adoption roadmap and build order

Initial assessment: 13 November 2026 at `9e636aa`, refreshed below through platform cycle 10 using implementation, test and container evidence. This is the implementation priority document for future OpenService work. The [compatibility ledger](servicenow-compatibility.md) describes supported behavior; this roadmap determines what to build next.

## Current assessment after the foundation tranche

The ten implementation cycles now provide versioned upgrades, stable user identities, revocable and time-bounded browser sessions, account activation/role management, password changes, lifecycle security events, manual invitation acceptance and administrator-assisted recovery. Evidence and exact per-cycle coverage are in [platform foundations](platform-foundations.md) and [validation](validation.md).

M0 remains the supported scope: a disposable local walkthrough with synthetic data. M1–M3 remain unmet. The next ready work is resource/field visibility and memberships (ADOPT-03/06), authentication abuse controls/MFA policy (ADOPT-08), and reliable verified delivery (ADOPT-07/04/05). Security-event retention/export and authentication-attempt logging remain partial (ADOPT-09). Restore/upgrade drills, monitoring, workload evidence and named support ownership remain pilot gates. Manual bearer links do not establish verified email, enterprise identity or customer isolation.

## Starting assessment at `9e636aa` (historical)

OpenService supports a local, disposable walkthrough with synthetic data. It does not yet provide a complete invitation/onboarding system or the controls needed for an independently operated team pilot. A shared instance is not ready for real customer or employee data on the evidence currently available.

The previous ten iterations added useful API capabilities, but their sequence did not close adoption prerequisites. Identity and operational gaps were recorded as limitations without an ordered build plan or milestone gates. The priority correction here is deliberate: resolve these prerequisites before optional API expansion or cosmetic social features.

Current account onboarding means an administrator creates a username, assigns one fixed role and supplies the password. There is no invite acceptance, verified email, account recovery, deactivation or role-edit workflow. All authenticated accounts can read all records, notes, attachments, exports and the record audit view. Group records and assignment fields do not provide membership or access enforcement.

Existing controls must be preserved: password hashing, session login/logout and CSRF protection, action-level role checks, record revisions/transactions, safe filename handling, size limits, parameter binding and non-root containers. These are useful foundations, not evidence that the missing controls below exist.

### Evidence and status vocabulary

- **Partial:** a bounded implementation exists; the named missing behavior is not covered by it.
- **Missing:** the inspected application/configuration has no implementation of the named capability.
- **Unverified:** configuration or functionality exists, but the required operational evidence has not been produced. This does not mean the capability is absent everywhere.
- **Unspecified:** an evaluator/product decision is still needed. Do not invent British Telecom requirements, identity-provider details, workload targets or approvals.

Evidence paths:

| Area | Evidence inspected |
|---|---|
| Identity and authentication | [Store.java](../src/main/java/ge/cynthia/openservice/Store.java), [Service.java](../src/main/java/ge/cynthia/openservice/Service.java), [WebController.java](../src/main/java/ge/cynthia/openservice/WebController.java), account/login templates, [local-account contract](api.md#local-accounts) |
| Membership, workflow and data types | [catalog.json](../src/main/resources/catalog.json), Service validation/record/update paths; group names, managers and assignees are stored values |
| Collaboration and audit | Record notes, attachment routes, record events, `/audit`, export paths; no delivery worker or security-event lifecycle |
| Migration | [Migration workbench](migration.md), import/export implementation; additive import and original-payload preservation |
| Runtime and delivery | [application.properties](../src/main/resources/application.properties), [pom.xml](../pom.xml), [CI](../.github/workflows/tests.yml), [cloud deployment notes](../terraform/README.md) |
| Existing verification | [Validation](validation.md), [iteration log](iteration-log.md), HTTP/workflow/Chromium tests; historical execution results are distinct from this documentation audit |

## Milestones and exit gates

| Milestone | Scope and exit evidence | Current position |
|---|---|---|
| M0 — Local feature walkthrough | Disposable local instance, synthetic data, named limitations, existing API/browser checks. A colleague can evaluate the implemented workflows. | Supported by existing test evidence. This is the current evaluation scope. |
| M1 — Managed shared evaluation | P0 requirements below complete; named accounts, invitations and recovery, revocable access, enforced visibility, protected identity endpoints, security events and reliable invitation delivery. Demonstrate two users with different access and a deactivated user losing access. HTTPS and an explicitly restricted evaluation environment are verified. | Not met. |
| M2 — Controlled operational pilot | M1 plus the agreed service workflow, appropriate enterprise identity, reliable notifications, migration reconciliation, restore/upgrade drills, monitoring and support ownership. Record data scope, workload, recovery targets and acceptance criteria with the evaluator. Complete applicable P1 requirements before using real data. | Not met; evaluator-specific requirements are unspecified. |
| M3 — Production adoption | M2 plus evidence for the agreed security, availability, performance, accessibility and governance requirements; tested release/recovery procedures and named operational owners. Any independent assessment findings are resolved or explicitly dispositioned by the responsible owner. | Not met; no production-readiness or vendor-parity claim. |

Use a dedicated instance for one organization as the provisional first-evaluation scope. Design ownership and membership explicitly. This does not justify unrestricted access within that organization. Shared multi-organization hosting remains out of scope until tenant isolation is implemented and tested. Confirm deployment and identity requirements before selecting the pilot architecture.

## P0 — Identity, access and safe shared evaluation

All P0 items are M1 prerequisites. An entry is a workstream; implementation must split it into bounded, independently testable changes without exposing a partially protected flow.

| ID | Workstream and starting state | Required completion evidence |
|---|---|---|
| ADOPT-01 | Evaluation scope and permission model — unspecified. Personas and customer/team boundaries are not defined. Maps to OS-005/014. | Record administrator, agent, requester and reviewer journeys; define organization/team ownership, visibility and administrative scope. Produce a resource/action matrix covering UI, APIs, counts, exports, notes, audit and attachment downloads. Identify which personas the first pilot actually requires. |
| ADOPT-02 | Versioned schema upgrades — partial. Bootstrap and one additive attachment upgrade exist. OS-002/014. | Repeatable, serialized migrations preserve current records, hashes, events and files on SQLite/PostgreSQL. Test upgrade from an existing installation, rerun, concurrent startup and failure recovery. Document backup and rollback/roll-forward procedures. |
| ADOPT-03 | Identity and membership model — partial. Users have a username, password hash and one role; groups are ordinary records. OS-005/009. | Stable user IDs, explicit account state, email/profile fields and real organization/group memberships. Preserve historic actor attribution and existing logins during upgrade. Enforce uniqueness and valid relationships; handle inactive assignees explicitly. |
| ADOPT-04 | Invitations and onboarding — missing. OS-005. | Administrator issues a scoped invitation; recipient verifies the intended address and sets their own password or joins through the approved IdP. Test pending/accepted/expired/revoked states, resend invalidation, single-use acceptance under concurrent requests, existing-account handling and role-escalation attempts. Store token digests; do not disclose tokens/passwords in logs or admin lists. |
| ADOPT-05 | Recovery, account management and offboarding — missing. OS-005/014. | Password change/reset, profile updates, controlled role changes, deactivation/reactivation and administrator recovery. Prevent accidental loss of the final usable administrator. Deactivation/recovery/role changes invalidate or reauthorize active sessions as appropriate; Basic/API access must honor account state too. Test old credentials, old sessions and concurrent changes. |
| ADOPT-06 | Authorization and isolation — partial action roles; resource/field rules are missing. OS-005/014. | Central policy enforces the agreed matrix on every access path, including counts/search, raw-source exports, audit records, both attachment-download routes and future notifications. Default to no access where ownership is undefined. Test direct IDs, alternate routes, cross-group/organization access and permission changes after login. Requester-visible content must be separated from internal notes. |
| ADOPT-07 | Reliable identity-message delivery — missing. OS-005/007/014. | Configured sender and delivery adapter, durable outbox/worker, bounded retries and observable failures. Test rollback without sending, worker restart, duplicate processing and invitation expiry while queued using a local mail sink. Configure and verify the selected real delivery service before M1; never assume successful queueing means delivery. |
| ADOPT-08 | Authentication/session abuse controls — partial sessions/CSRF; explicit throttling and lifecycle controls are missing. OS-014. | Bound authentication, invitation and reset attempts; avoid account enumeration; define idle/absolute expiry, session revocation and recovery behavior. Preserve CSRF/session fixation protections. Document deployment limits and test overload/abuse behavior without locking out legitimate administrators. |
| ADOPT-09 | Security audit — partial record-change history. Identity/security events and restricted audit access are missing. OS-007/014. | Record account/invite/role/membership changes, recovery, relevant authentication/authorization events and sensitive exports with actor, outcome and correlation context. Restrict event access; exclude secrets, token values and unnecessary personal data. Define retention and protected export. Record history alone does not satisfy this requirement. |

## P1 — A service operation that can be adopted

These are pilot/production workstreams, not claims about requirements already agreed with British Telecom. Select the precise workflow with the evaluator, but do not waive the identity, isolation, recovery or evidence gates for real-data use.

| ID | Workstream and starting state | Required completion evidence |
|---|---|---|
| ADOPT-10 | Operational workflow and data integrity — partial record CRUD/local states. Real assignment, typed references, approval records and fulfillment are incomplete. OS-002/006/009/013. | Demonstrate intake → validated assignment → work/approval → resolution/reopen for the chosen service. Validate active assignee/group eligibility, relationships, allowed state transitions, timestamps and concurrent updates. Provide queue/ownership views and the operational reports needed for that workflow. Separate labels stored in JSON from functioning domain relationships. |
| ADOPT-11 | SLA timers and escalation — missing. OS-006/013. | Defined response/resolution clocks, business calendars/time zones, pause/resume and escalation rules. Deterministic clock tests and restart/retry tests prove deadlines survive outages without duplicate escalation. Depends on durable jobs and actual assignments. |
| ADOPT-12 | Collaboration and requester experience — partial internal notes/activity. Notifications, mentions/watchers and requester-specific views are missing. OS-003/007/013. | Reliable assignment/comment/approval notifications with preferences; permission-aware mentions/subscriptions and a scoped requester journey. Verify removal of access also removes future notification visibility. Add customer-visible replies only with tested internal/public separation. |
| ADOPT-13 | Enterprise identity and integration authentication — missing SSO/MFA/scoped service credentials. OS-005/012/014. | Confirm evaluator IdP and integration needs; implement the appropriate federation/MFA and provisioning/deprovisioning path, with controlled recovery access. Test disabled identities, role/group mapping, logout/revocation and independently revocable integration credentials. A generic “SSO supported” label is not acceptance evidence. |
| ADOPT-14 | Migration and required integrations — partial additive JSON/CSV import. Attachments/history/reference resolution, differential migration and cutover are incomplete. OS-008/015. | Reconcile users/groups, reference targets, states, notes and files; report unsupported mappings. Test retry/idempotency, interrupted batches and counts/checksums on a representative synthetic fixture, then an authorized pilot sample. Rehearse cutover and rollback; verify the intake/email, webhook, identity and downstream integrations actually needed for the pilot, including authentication, duplicate delivery and failure recovery. |
| ADOPT-15 | Backup, restore and safe upgrades — partial cloud backup configuration; restore drills and upgrade recovery are unverified. OS-010/014. | Agree recovery time/data-loss targets. Restore records, identities, audit and attachment bytes into a clean environment; demonstrate application upgrade and rollback/roll-forward. Include secrets/configuration recovery and name the operator responsible. Container replacement against the same database is not a restore test. |
| ADOPT-16 | Data governance and attachment safety — partial safe download/size controls; application retention/privacy-deletion workflows and malware handling are missing. OS-007/008/014. | Define handling for live records, retained import payloads, audit snapshots, backups and exports. Implement permission-aware export/deletion/retention and attachment quarantine/scanning as required by the data scope. Verify sensitive data is not retained through an overlooked copy. Record organizational requirements; do not claim legal or regulatory compliance from these features. |
| ADOPT-17 | Monitoring and operating procedures — partial database health probe/runtime logs; service metrics, alerts and incident runbooks are unverified or absent from the repository. OS-010/014. | Correlate failures, latency, authentication events, database pressure and background-job outcomes. Define useful alerts, secret redaction, dashboards, support ownership and outage/recovery runbooks; demonstrate a failure is detected and handled. A 200 health response is insufficient evidence. |
| ADOPT-18 | Capacity and availability — unverified workload capacity; deployment starts with one application instance and local sessions. OS-010/014. | Agree realistic concurrency, record/file volume and latency targets. Measure search/count/export, lock contention, uploads and worker behavior under load. Bound large exports/queries and test database failure. Verify any proposed multi-instance/session/failover design before claiming availability targets. |
| ADOPT-19 | Release security and usability — partial functional/coverage CI and responsive Chromium journeys. Security assessment, dependency/image scanning, release provenance and accessibility assessment are unverified. OS-003/010/014. | Threat-model the selected deployment, map applicable security verification requirements, scan dependencies/images/secrets, record an SBOM and reproducible release evidence, and maintain a vulnerability response process. Test keyboard/screen-reader journeys, error recovery and supported browsers. Pin acceptance targets and supported versions instead of inferring them from screenshots or coverage. |

## Later, after the adoption prerequisites

ADOPT-20 covers optional social polish: avatars, reactions, richer profiles, discovery and personalization. Core team membership, scoped collaboration and notifications belong above and must not be deferred as cosmetic features. Build additional ServiceNow APIs, full CMDB reconciliation, workflow designers, catalog breadth, native scripting and wider product parity only when they serve an agreed use case and their prerequisites are met. Arbitrary scripting requires a separately reviewed isolation design.

## Dependency order and next implementation

1. Finish the ADOPT-01 scope/permission matrix for the provisional single-organization evaluation. Unknown evaluator details stay explicitly unconfirmed.
2. Implement ADOPT-02 upgrade infrastructure, then the ADOPT-03 identity/membership model. Add stable identities before building invitation URLs around mutable usernames.
3. Establish account-state/session enforcement and the security-event foundation from ADOPT-05/08/09. Build ADOPT-06 visibility checks across all access paths before exposing new collaboration or requester flows.
4. Implement ADOPT-07 delivery and ADOPT-04 invitation acceptance, followed by the rest of ADOPT-05 recovery/administration. End with a complete admin → invite → recipient-set credentials → scoped work → deactivate journey.
5. Close the remaining P0 acceptance cases and run the M1 evaluation gate. Then work through the agreed P1 service/pilot scope and recovery/operational evidence.

At the starting assessment, migration and identity foundations were the next work after recording the permission model. Use the current assessment above for the next selection. Split each workstream into bounded slices with its dependency, production scope, behavior, tests and acceptance evidence. Do not treat a database table, unused helper or UI placeholder as a completed capability. No calendar estimate or evaluator approval is implied by this sequence.

## Rules for subsequent development cycles

- Read this roadmap and the compatibility ledger before selecting a gap. Choose the highest-priority ready prerequisite. Do not select optional query/API enhancements while ready P0 work remains; if an external dependency blocks an item, record it and continue with another P0 prerequisite. A direct user instruction can change the order explicitly.
- Record an owner, workstream ID, dependencies and concrete acceptance cases before implementing each slice. Keep status partial until the complete user/API flow and its access boundaries are tested. Future feature commits should update this roadmap as well as the compatibility ledger.
- Retain the required latest mutually compatible stable Java/Spring Boot stack and exact coverage gates. Verify version selections against official sources when implementing/upgrading; current pins are not a permanent ceiling.
- Run meaningful positive, negative, authorization, migration and concurrency cases on both supported databases, with browser journeys for user-facing work. Exact line/branch/method coverage remains necessary for touched production files; acceptance, security, capacity and recovery evidence are separate gates.
- Report code coverage, implemented requirements and release readiness separately. Unknowns stay unknown. Do not fabricate requirements coverage percentages, classify configuration as a completed restore/HA test, or infer production readiness from passing unit tests.
- Reassess the milestone gates after each workstream and before presenting a shared evaluation or pilot as ready. This roadmap does not authorize publishing, deploying, contacting evaluators or sending invitation emails.

## Decisions to confirm with the evaluator

The actual BT use case/personas, required integrations, IdP/provisioning, data sensitivity, team/customer boundaries, deployment/network constraints, workload, recovery targets, accessibility needs and operating/support owners are not yet confirmed. Capture them as acceptance criteria before a pilot; do not assume a ServiceNow replacement requires every feature in the reference inventory at once.

Security verification planning should use a versioned [OWASP ASVS](https://asvs.owasp.org/) requirements set appropriate to the agreed scope, rather than an invented certification claim. The permission matrix and negative tests draw on [OWASP authorization guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html); recovery-token lifecycle and enumeration controls draw on [OWASP password recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html). Sources reviewed 13 November 2026. Invite-specific state and delivery requirements above are proposed OpenService design decisions, not vendor-conformance claims.

## Platform implementation tranche

The [platform implementation contract](platform-foundations.md) records the provisional ADOPT-01 permission matrix, ordered ADOPT-02 upgrades, per-cycle acceptance and QA evidence. The initial assessment above is retained as the starting snapshot. ADOPT-02 now has a migration journal and startup serialization; backup restoration and upgrade recovery procedures still need operational drills. All M1–M3 gates remain unmet.

ADOPT-03 now includes stable user identities, migration/backfill, session binding and a current-account API. Memberships, profiles/email and lifecycle controls remain open; this does not complete ADOPT-03 or M1. See platform cycle 2.

ADOPT-05/08 now include persistent all-browser session revocation and an account-security page. Account deactivation, role changes, password management, rate limits and absolute session lifetime remain open. See platform cycle 3.

ADOPT-05 now includes administrator-controlled deactivation/reactivation, cross-entry-point account-state enforcement and concurrent last-active-administrator protection. Role changes, password recovery and complete offboarding/ownership transfer remain open. ADOPT-09 security events and ADOPT-06 resource isolation are still prerequisites. See platform cycle 4.

ADOPT-05 now also includes guarded local role changes with session revocation and concurrent final-administrator protection. Password management, group memberships, scoped permissions and lifecycle audit remain open. See platform cycle 5.

ADOPT-05/08 now include self-service password changes with current-secret verification, concurrent-update protection and session revocation. Forgotten-password recovery, invitations, security-event audit, password screening and authentication throttling remain open. See platform cycle 6.

ADOPT-09 now includes a transactional lifecycle-event journal and administrator UI/API with secret exclusion. Authentication attempts, retention/export, tamper-resistant storage and SIEM forwarding remain open. See platform cycle 7.

ADOPT-08 now includes an eight-hour absolute browser-session limit, explicit 30-minute servlet idle timeout and fail-closed timestamp validation. MFA, throttling, password screening, distributed-session operations and evaluator-specific policy remain open. See platform cycle 8.

ADOPT-04 is now partial: manual single-use, 24-hour invitation issuance, replacement/revocation and recipient-set credentials are implemented with audit and concurrency evidence. Reliable email delivery, address verification, membership/resource scope, provider-based onboarding and abuse controls remain open; M1 is still unmet. See platform cycle 9.

ADOPT-05/08/09 now include administrator-assisted, reauthenticated 15-minute recovery links with target/issuer revision binding, explicit revocation, atomic credential/session replacement and lifecycle events. Manual recipient verification/delivery, no-administrator recovery, throttling and full identity integrations remain open. See platform cycle 10. All ten cycles are local commits; no publishing or deployment is authorized by this tranche.

Cycle 11 / ADOPT-06: private-account record visibility now spans UI/API reads and mutations, lists/counts/export/audit/attachments, including retained audit after deletion. Existing records remain workspace-visible until explicitly restricted. Field ACLs, requester defaults, group memberships and multi-organization isolation remain open; M1 is unmet.

Cycle 12 / ADOPT-03/06: persistent group membership now has administrator UI/API, idempotence, audit and deletion cleanup. Inherited roles, team record visibility, requester defaults and field ACLs remain separate work. M1 remains unmet.
