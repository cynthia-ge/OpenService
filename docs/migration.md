# Migration workbench

## Safe first move

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

Maximum 10,000 records per file and 6 MB per request. Split larger exports into batches. This version performs an additive import, not differential synchronization; it never deletes local records based on a missing source row. Concurrent imports of the same new IDs are not supported; run one migration job at a time. There is no background queue or automatic retry.

Attachments, journal histories, passwords, ACLs, flows, scripts, catalog configuration and reference-target resolution require separate migration work. The upload feature accepts new supporting files, but does not fetch ServiceNow attachment bodies. Test these dependencies before calling a migration complete. Source values beyond database column limits can still fail transactionally at commit; keep original exports and resolve the validation issue before retrying.

No connector credentials or real customer data are bundled. The demo records in the browser journey are synthetic.
