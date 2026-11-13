# ServiceNow compatibility ledger

Baseline: empty Git repository. The user expanded the first iteration from one feature into a broad, connected foundation with a polished UI, migration and three-cloud Terraform. Reference inventory: `../skills/open-service/references/servicenow-blueprint.md`.

Target: a bounded subset of the **Australia Table API documentation**, accessed 2026-11-13, not verified Australia Patch 6 runtime parity. [Primary reference](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html). The generic docs URL returned Brazil; no silent mixing of release contracts is intended. There is no licensed-instance conformance capture.

| ID | Inventory | Capability | Status and boundary |
|---|---|---|---|
| OS-001 | A4(a) | Table CRUD and JSON envelopes | Implemented subset: seven configured tables, unversioned routes, bounded parameters. API details in `api.md`. |
| OS-002 | A1 | Shared schema and persistence | Partial: registry drives validation and forms. No native sys_db_object/sys_dictionary CRUD, table inheritance or typed reference resolution. |
| OS-003 | A2 | Workspaces, forms, lists | Implemented original UI: overview, record forms, search, paging, dictionary, history and migration. No native UI16/g_form compatibility. |
| OS-004 | A4(d) | Encoded queries | Partial: equality, inequality, contains, starts-with, IN/NOT IN, AND and ordering; database filtering. No full grammar or scripting. |
| OS-005 | A2/A4(e) | Identity and access | Partial: local admin/agent/reader, administrator account-onboarding workspace, Basic and session auth, CSRF. No password reset/deactivation, OAuth/SSO, field ACLs or domain separation. |
| OS-006 | A3 | Incident/change/request/problem workflows | Partial: local state machines, approval by admin, revision checks. Not verified vendor state/BR semantics. |
| OS-007 | A1/A4(a) | Audit, journal and files | Partial: persistent record events, internal notes and attachments. No native journal/Attachment API or full activity semantics. |
| OS-008 | Recommendations | Migration/import/export | Implemented bounded JSON/CSV path with dry-run, source retention, stable IDs and repeat-import skips. See migration limits. |
| OS-009 | A3 | Configuration items/knowledge/groups | Partial: real CRUD workspaces; not full CMDB/IRE, knowledge publishing or group membership. |
| OS-010 | Platform operations | Docker and multi-cloud infrastructure | Docker verified locally; AWS/GCP/Azure validated and mock-plan tested, not deployed. |
| OS-011 | A1/A4(c) | Business rules and Glide runtime | Missing; prioritize a sandboxed, explicitly scoped implementation with source-backed semantics. |
| OS-012 | A4(a) | Aggregate, native attachments, Import Set and Batch APIs | Partial: filtered/grouped count Aggregate API added; numeric aggregates, native attachments, Import Set and Batch remain missing. |
| OS-013 | A1/A5 | Flow Designer, SLAs, catalog, update sets | Missing; no placeholder interfaces claim support. |
| OS-014 | Enterprise operation | SSO, fine-grained ACLs, rate limits, workers, migrations | Missing; required before production adoption. |
| OS-015 | Migration | Direct connector, attachments, journals, differential sync | Missing; next adoption-focused tranche. |

## Acceptance gates

- Real CRUD persists and is shared by UI/API; all seven record types render and edit.
- Readers cannot mutate; agents cannot approve changes or delete; session mutations require CSRF.
- Invalid bodies, fields, parameters and transitions fail; stale revisions conflict.
- Bound database filters preserve literal wildcard characters and resist query injection.
- Import preview writes nothing; errors prevent partial writes; retries skip existing records; original source exports round-trip.
- Attachments persist and download as files; deletion removes files but preserves audit events.
- Local Docker PostgreSQL and browser journeys are tested separately from the SQLite fast suite.
- Coverage includes every application Python module, with 100% lines and branches. Templates/CSS are checked through rendered views and browser journeys; a Python coverage percentage does not measure visual correctness or Terraform.
- Terraform uses provider validation and mock-provider plan assertions for each target; no claim of a live cloud deployment.

Reproduce results with the commands in the README. Record the final measured counts and checks in `validation.md`. Future iterations should close one ledger gap at a time unless the user expands scope again.

## Count aggregation iteration — 13 November 2026

[Primary Aggregate API reference](https://www.servicenow.com/docs/r/api-reference/rest-apis/c_AggregateAPI.html?contentId=z1y5kSDzdmS5hEs40YMlSQ), Australia documentation updated 2026-04-21. Implements stored-value counts over the full filtered selection, optional groupings and COUNT/field ordering. Numeric aggregate functions and display-value resolution are explicitly rejected. Baseline HEAD: `4360f0a868633f7b7dba9b3b2e415da1d5bcf4c5`.

## OS-004 continuation — membership filters, 13 November 2026

Baseline HEAD 512a471, main, clean tree. Blueprint A4(d). Selected the missing IN/NOT IN subset over native attachments (OS-012, missing) and upgrade migrations (OS-014, missing), extending the common database selection layer used by table and aggregate reads.

Contract: Australia encoded-query operator documentation, dated 2026-03-12, retrieved 2026-11-13 from the [official Australia documentation source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-user-interface/r_OpAvailableFiltersQueries.md). The generic documentation route now returns Brazil; Australia source is the selected evidence. Literal stored-value comma-list matching is implemented; local limits and empty/null behavior are specified in api.md. No vendor-instance conformance claim.

Implementation service.py; test_membership.py has 10 acceptance cases. Two positive regressions failed before implementation. Checks cover both APIs, AND/order/pagination/count/group composition, IDs, duplicate and missing values, exact literal wildcard/quote/space data, role/authentication boundaries, unknown fields and malformed/oversized lists. SQLite: 88 tests, 515/515 lines and 180/180 branches including the setup helper; service.py 160/160 lines and 76/76 branches. Runtime alone is 499/499 lines and 178/178 branches.

The same 88-test suite and full line/branch coverage also pass against PostgreSQL 17 in disposable Docker containers. No demo database or external ServiceNow instance was used.

## OS-005 continuation — local account onboarding, 13 November 2026

Baseline HEAD `f4a2d3e`, branch `main`, clean tree; baseline suite: 88 passing tests. Blueprint A2 and A4(e). Account creation existed only through an administrator API, making a multi-person browser evaluation depend on API tooling. Selected a complete administrator account-list/create workflow. Native Attachment API (OS-012, missing), versioned schema upgrades (OS-014, missing) and real assignment/group membership (OS-009, missing) remain candidates for later iterations.

Primary contract evidence, retrieved 2026-11-13:

- [Create a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_CreateAUser.md), updated 2026-03-12: privileged user administration, unique usernames and a create/list workflow.
- [Assign a role to a user, official Australia source](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/user-administration/t_AssignARoleToAUser.md), updated 2026-09-10: privileged role assignment and additional restrictions on granting administrator access.

The generic documentation route now returns Brazil. The release-specific Australia source above supports the conceptual workflow; this implementation is an original OpenService local-account contract, **not** `sys_user` API or vendor-instance conformance. OpenService requires its own `admin` role and assigns one of three local roles at creation. It does not reproduce `user_admin`, role inheritance, profile fields or the vendor password-setting workflow.

Implemented slice: `GET/POST /admin/accounts`, administrator-only navigation, 25-account pages, shared API/browser validation and password hashing, reader default, inline errors, duplicate protection and immediate sign-in. Detailed input/status/serialization boundaries are in [API scope](api.md#local-accounts). The users table is unchanged. Passwords now have an explicit maximum of 1024 characters on both creation entry points.

Acceptance checklist:

- [x] Administrator lists names/roles, creates a persisted account and sees confirmation; the account can sign in immediately.
- [x] Reader/agent cannot access account administration or elevate privileges; missing/invalid session CSRF cannot create an account.
- [x] Both creation paths reject invalid types, names, passwords and roles; maximum accepted lengths work; passwords/hashes are never returned in the listing or error form.
- [x] Duplicates preserve existing credentials/role; an actual unique-constraint failure rolls back and leaves the database session usable.
- [x] Pagination is bounded and ordered; invalid, repeated, unknown and out-of-range parameters fail explicitly.
- [x] Desktop/mobile Chromium verifies the complete form and subsequent agent/reader access, including absence of document overflow and browser script errors.

Implementation: `accounts.py`, `app.py`, `accounts.html`, `base.html`, `app.css`. Acceptance: 24 new pytest cases in `test_accounts.py`, plus `accounts_browser_journey.py`; the initial creation/access regressions failed with 404 against the baseline. All 112 tests pass on SQLite and PostgreSQL 17. Exact counts and reproduction are in [validation](validation.md#account-onboarding-iteration): touched Python files 289/289 executable lines, 96/96 branches and 26/26 named functions; all runtime Python 550/550 lines, 192/192 branches and 41/41 functions. Templates/CSS are exercised in Chromium, not included in Python coverage percentages.

Only the local onboarding slice is implemented. OS-005 remains partial: no account editing, password reset, deactivation, SSO, inherited roles, customer separation or field/record ACLs. The evaluator walkthrough records these boundaries; this does not establish enterprise production readiness.
