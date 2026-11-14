# Migration workbench

The connected workflow below and the older file-upload importer are separate contracts.


## Connected migration

Open **Migration studio → Connect a ServiceNow instance** (`/admin/migrations`) as an administrator. The guided workflow supports read-only OAuth bearer access, persisted keyset page checkpoints, administrator-only staging, atomic preview/apply, repeat synchronization and a recorded cutover decision. Credentials are supplied for each fetch request, remain in page memory, and are never stored in job definitions or database checkpoints. Use HTTPS for shared deployments.

1. Create a plan with a source origin, selected source tables and explicit field/value mappings. A destination field maps to a source field. Values must fit the destination schema and state model. Built-in/custom table names and source IDs are not assumed equivalent without this mapping.
2. Fetch using an authorized token with access to the selected Table API resources. Each source page is saved atomically; retries resume after the last committed `sys_id`. Stop pauses after the current page; reopening the browser requires the token again. A failed page leaves its checkpoint untouched. There is no background worker after the browser closes.
3. Review counts, source archives and mappings. Preview exercises the actual promotion transaction and rolls it back, including account and configuration changes. No source script is executed.
4. Apply after review. New records retain their source IDs and available number/timestamps, but become private to the importing administrator. Source metadata must be text: timestamps up to 40 characters, actor names up to 80, and numbers up to 65,536. Only explicitly mapped fields enter operational records; raw staged source data remains administrator-only. Forward and cyclic selected record references are validated after all records are inserted, inside the same transaction.
5. Freeze source writes externally. Create a final synchronization, fetch and apply it, reconcile the source independently and review the limitations. Synchronization rescans selected tables, skips unchanged correlated items and updates changed items only when the destination still matches its prior snapshot. Conflicting local edits, identifier/name collisions and stale jobs fail atomically. Missing source rows are reported, never interpreted as permission to delete destination records.
6. Confirm source freeze, completeness, permissions and rebuilt automation. Cutover verifies destination snapshots and missing-item reports and records an audited decision. It requires the latest applied synchronization. It does not switch DNS, integrations, identity providers or other external traffic. Activate imported accounts and grant reviewed record access after the final cutover, using the existing administration controls.

### Example plan

```json
{
  "source": "https://your-instance.service-now.com",
  "tables": {
    "sys_user_group": {
      "target": "sys_user_group",
      "fields": {"short_description": "name", "description": "description"},
      "values": {}
    },
    "incident": {
      "target": "incident",
      "fields": {"short_description": "short_description", "state": "state", "priority": "priority"},
      "values": {}
    }
  },
  "identities": true,
  "roles": {"itil": "agent"},
  "history": true,
  "attachments": true
}
```

This example must be reviewed against the source's configured state values, roles and required fields. `roles` explicitly maps source role names to local `reader`, `agent` or `admin`; the highest explicitly mapped role wins. Unmapped roles grant no extra privilege and are listed in the report. New accounts default to `reader` when no role is mapped. All imported accounts start disabled with an unshared random password; source passwords/hashes and sessions are never imported. Non-alphanumeric source usernames receive the deterministic local name `sn<source_sys_id>`; the source name remains in staging. A changed imported account is disabled again on sync. Typed assignment fields that require active accounts cannot point to these disabled accounts during import; defer those assignments until activation and reviewed handover. Existing unrelated accounts are not adopted or overwritten.

Identity migration reads `sys_user`, `sys_user_role`, `sys_user_has_role`, `sys_user_grmember` and selected `sys_user_group` records. Membership is distinct from a role grant. Programmable ACLs, field visibility and domain separation do not have an automatic equivalent; private staging and disabled accounts are deliberate review boundaries, not permission parity. Source system tables may require additional source-side grants.

Attachment metadata and binary bodies are fetched separately; sizes are checked and SHA-256 destination fingerprints detect later changes. Available `sys_journal_field` and `sys_audit` rows preserve their original author/time/classification fields in the administrator-only archive, with cursor pagination. They are **not merged into the normal activity timeline** and are not exposed to ordinary readers. Retention, ACLs and source API availability limit what can be obtained; deleted or never-recorded history cannot be reconstructed. Independently reconcile counts/checksums on an authorized source sample before accepting completeness.

### Configuration translation

A plan may include `configuration` containing a reviewed [OpenService configuration bundle](configuration.md). Typed schemas, reference relationships, choice values, supported state workflows and local permission rules are installed in the same transaction as records. `tables.<source>.values.<destination-field>` translates explicit scalar values. Synchronization retains the mapping and does not reinstall the original configuration version.

This is an explicit mapping/import mechanism, **not an automatic translator for ServiceNow update sets, dictionary inheritance, Flow Designer flows, JavaScript, ACL scripts or integration credentials**. Those require assessment and reviewed equivalent implementations. No proprietary runtime or source logic is copied or executed. Universal one-click conversion remains unsupported.

### Limits and endpoints

Default origins are HTTPS, single-label `*.service-now.com`, without credentials, paths, queries or an explicit port. Deployment owners may allow exact custom origins with comma-separated `MIGRATION_ALLOWED_ORIGINS`; HTTP should only be used with disposable local test services. Redirects are never followed. Re-check the allowlist on every fetch. Source responses have 30-second request and 10-second connect timeouts, a 6 MiB JSON page limit, 100 rows per table page (one attachment per page), 10,000 staged rows per job, 50 MiB of staged JSON characters, 5 MiB per attachment and 50 MiB total attachment bodies. All read/write source operations are GETs. No source credentials appear in error responses.

Jobs are bounded evaluation migrations, not a large-instance migration service. Fetching holds the application's mutation transaction during source requests (one JSON request and, for an attachment page, one binary request, each limited to 30 seconds); background queues, parallel workers and large-volume operational qualification remain roadmap work. Use one coordinated mapping/scope per source origin: correlations and missing-item reconciliation are shared by origin. Apply preserves current account/record access changes by detecting conflicts rather than overwriting them. Missing/deleted items and deliberate destination edits require operator reconciliation; an automated delete/conflict-resolution tool is not included.

Administrator-only API routes (cookie sessions require `X-CSRF-Token`; JSON POST bodies are required):

| Route | Method and behavior |
|---|---|
| `/api/openservice/migrations` | GET saved jobs; POST a plan, returns 201 |
| `/{job}` under that prefix | GET plan, checkpoint, counts and limitations |
| `/{job}/step` | POST `{}` with `X-Source-Token`, fetch and persist one page |
| `/{job}/preview` | POST `{}`, validate and roll back all promotion writes |
| `/{job}/apply` | POST `{}`, atomic promotion; returns counts and unmapped/missing reports |
| `/{job}/sync` | POST `{}`, create a fresh full-rescan job from an applied job |
| `/{job}/archive?table=…&after=…` | GET up to 100 source rows; pass the last source ID as `after` |
| `/{job}/cutover` | POST all four boolean confirmations from the wizard |

Re-fetching a completed job or applying an already applied/stale job returns 409. Source failures return sanitized 502 responses; invalid plans/data return 400. A transport failure does not advance checkpoints. A failed apply writes no partial users, files, records, configuration or correlations.

The protocol fixture and browser data are synthetic. A real ServiceNow instance, its permissions, API limits and retained history have **not** been verified in this release. Official contract references reviewed 2026-11-14: [Table API](https://www.servicenow.com/docs/r/api-reference/rest-apis/c_TableAPI.html), [Attachment API](https://www.servicenow.com/docs/r/api-reference/rest-apis/c_AttachmentAPI.html), [journals](https://www.servicenow.com/docs/r/api-reference/web-services/retrieving-journal-entries.html), [audit/history distinction](https://www.servicenow.com/docs/r/platform-security/c_DiffBtwnAuditHistSets.html), and [update sets](https://www.servicenow.com/docs/r/application-development/system-update-sets/exploring-system-update-sets.html). Current Brazil documentation informs research; it does not establish conformance against the project's provisional Australia baseline.

## File-upload import

1. Select a supported table: `incident`, `problem`, `change_request`, `sc_request`, `cmdb_ci`, `kb_knowledge`, or `sys_user_group`.
2. Export records from your authorized ServiceNow account as Table API JSON (`{"result":[...]}`) or CSV including `sys_id`. Use raw stored values, not display values. Page through the source to collect the whole table; the Table API may limit each response.
3. Open **Migration studio**, choose the destination and file, and keep **Dry run** selected.
4. Review validation errors, prospective creates, existing-record skips and source-only fields. Unknown columns are preserved, not discarded or treated as working platform features.
5. Select **Import** and submit the same file. Any validation error prevents the entire batch from being written.
6. Verify counts, sample records and a source export before changing existing integrations or retiring the source system.

## What survives

- Original 32-character lowercase hexadecimal `sys_id` and record number.
- Creation/update timestamps and actor names, when provided.
- Supported editable fields; reference objects use their raw `value` for the working record.
- Every source field, nested object and source value in the stored original JSON payload.
- A local import event in the audit trail.

An original source export is available from `/export/{table}?source=true`. The ordinary export combines preserved source-only fields with current OpenService values. Source payload retention is useful for later migration stages; it is not equivalent to implementing those fields. CSV only preserves information actually present in the CSV file; it cannot reconstruct nested data omitted by the exporter.

Repeated imports skip existing IDs without modifying them. Duplicate IDs inside a single file, invalid IDs or collisions with another table are validation errors. Source users are not automatically created as login accounts. Imported states are retained even if they are outside the local workflow; unsupported states will need explicit mapping before workflow transitions can be used.

## Limits and next steps

Maximum 10,000 records per file and 6 MB per request. Split larger exports into batches. The file-upload importer performs an additive import, not differential synchronization; it never deletes local records based on a missing source row. Concurrent imports of the same new IDs are not supported; run one migration job at a time. There is no background queue or automatic retry.

The file-upload importer does not migrate attachments, journal histories, passwords, ACLs, flows, scripts, catalog configuration or resolve reference targets. The upload feature accepts new supporting files, but does not fetch ServiceNow attachment bodies. Test these dependencies before calling a migration complete. Source values beyond database column limits can still fail transactionally at commit; keep original exports and resolve the validation issue before retrying.

No connector credentials or real customer data are bundled. The demo records in the browser journey are synthetic.
