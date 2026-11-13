# ServiceNow compatibility ledger

Baseline: empty Git repository. The user expanded the first iteration from one feature into a broad, connected foundation with a polished UI, migration and three-cloud Terraform. Reference inventory: `../skills/open-service/references/servicenow-blueprint.md`.

Target: a bounded subset of the **Australia Table API documentation**, accessed 2026-11-13, not verified Australia Patch 6 runtime parity. [Primary reference](https://www.servicenow.com/docs/r/australia/api-reference/rest-apis/c_TableAPI.html). The generic docs URL returned Brazil; no silent mixing of release contracts is intended. There is no licensed-instance conformance capture.

| ID | Inventory | Capability | Status and boundary |
|---|---|---|---|
| OS-001 | A4(a) | Table CRUD and JSON envelopes | Implemented subset: seven configured tables, unversioned routes, bounded parameters. API details in `api.md`. |
| OS-002 | A1 | Shared schema and persistence | Partial: registry drives validation and forms. No native sys_db_object/sys_dictionary CRUD, table inheritance or typed reference resolution. |
| OS-003 | A2 | Workspaces, forms, lists | Implemented original UI: overview, record forms, search, paging, dictionary, history and migration. No native UI16/g_form compatibility. |
| OS-004 | A4(d) | Encoded queries | Partial: equality, inequality, contains, starts-with, AND and ordering; database filtering. No full grammar or scripting. |
| OS-005 | A4(e) | Identity and access | Partial: local admin/agent/reader, Basic and session auth, CSRF. No OAuth/SSO, field ACLs or domain separation. |
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
