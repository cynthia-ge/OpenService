# Reusable application configuration

Administrators can now define and promote tables, validation and state workflows through **Configuration** in the workspace navigation (`/admin/configuration`). Preview validates a package without saving it; install saves the complete package atomically. The same definitions immediately drive the existing workspaces, record forms, Table API, filtering, aggregate counts and migration paths. This is original OpenService behavior aligned with bounded ServiceNow capabilities; it is not ServiceNow update-set/XML, dictionary API or workflow-runtime compatibility.

Start with [the service operations package](../examples/service-operations.configuration.json). It defines reusable service cases, assets with dependency references, and a knowledge authoring lifecycle. Paste its JSON into the configuration workspace, preview, then install. New tables appear in navigation. Use synthetic data; existing M1–M3 adoption gates still apply.

## Package and revision contract

`GET /api/openservice/configuration` downloads `{ "format": "openservice.configuration.v1", "tables": { ... } }`. It includes current table definitions and integer versions, and contains no business records, users or credentials. Administrator access is required. `POST` accepts the same shape; `?dry_run=true` is the default. Use `?dry_run=false` to install. Unknown/repeated query parameters and unsupported package keys are rejected.

Packages contain 1–50 tables. New table names match `u_[a-z][a-z0-9_]{0,60}` and start at version 0. Existing tables require their **destination's current version**; successful installation increments it. Replaying a stale package returns 409. Export and reconcile the destination before promotion between environments: a source revision is not automatically a destination revision. Concurrent installs of the same version yield one success and one conflict. Preview reports proposed versions but changes neither configuration nor audit history. Installation records a security event with affected versions. It does not retain a complete version history or automatically roll back business data.

Each definition has `version`, `label`, `prefix`, `fields`, `states` and `flow`; optional properties are `field_rules`, `transition_rules` and `assignment`. `fields` maps safe lowercase identifiers to labels and must include `short_description`. There are at most 100 fields and 50 states; system fields and generated numbers are reserved. Removing existing fields is rejected. Existing records are validated before an altered definition can be installed. Dependent tables can appear in any order within one package. Missing dependencies or invalid persisted data reject the whole installation.

## Typed values and relationships

`field_rules` maps declared field names to rules. Every rule specifies a `type`, optional boolean `required` and optional `max_length` from 1–65536. Table API wire values remain strings:

| Type | Accepted nonblank values |
|---|---|
| `string` | Text, subject to length and required rules. |
| `integer` | Signed 64-bit integer text. |
| `decimal` | Decimal text with optional leading minus and fractional digits; no exponent, NaN or infinity. |
| `boolean` | `true` or `false`. |
| `date` | Valid ISO local date, such as `2026-11-14`. |
| `datetime` | An ISO instant accepted by Java `Instant.parse`, such as `2026-11-14T10:15:30Z`. |
| `choice` | One of the rule's explicit string `choices`. |
| `reference` | The ID of an existing record in the rule's `target` table that the acting user can access. |
| `account` | A stable ID of an active local account. |

Optional blank values represent no value. The existing nonblank summary, 65536-character wire-value bound, priority 1–5 and configured-state constraints still apply. Rules are enforced on create/update and on imports into versioned tables. Imports may load any declared state; they validate the destination state's required fields, but do not replay transition history. Target records must already exist before a referencing record is imported; forward references within a record import are not resolved. Existing unversioned tables retain their legacy import behavior until explicitly configured.

Typed references prevent deleting a record while another live record refers to it, including dependencies hidden from the deleting actor. The error does not reveal those records. Existing reference values are revalidated when their source record is saved; visibility and account-state changes can require an administrator to clear or reassign a stale reference. Existing free-text assignment fields are not silently converted.

`assignment: { "user_field": "assigned_to", "group_field": "assignment_group" }` requires an `account` field and a reference to `sys_user_group`. A nonempty assignee must be an active agent/administrator and a current member of the selected group. Unassigned intake is allowed. Membership removal and account deactivation do not automatically reassign existing work.

## Configurable workflow policies

`states` maps state IDs to labels; its first state is the creation state. A state field and a nonempty state model must occur together. `flow` maps source states to allowed target lists. `transition_rules` can restrict entry to a state to `roles` (`admin` and/or `agent`) and require populated `required_fields`. Required fields remain enforced on later edits in that state. Changes use existing record revision checks and audit history. Built-in change-request administrator approval restrictions remain enforced.

The example knowledge table provides draft/review/published/retired transitions. **Publication state does not change read visibility:** use the existing record-access controls; there is no public/requester knowledge portal in this slice. The example asset dependency is a validated relationship, not discovery, impact correlation or a complete CMDB. State-entry approval restrictions are not separate approval requests, delegations or quorum decisions.

## Operational and compatibility evidence

SQLite write transactions and a shared PostgreSQL advisory lock serialize HTTP mutations so configuration checks, reference writes and deletions cannot race each other. This favors correctness for the current single-organization deployment; throughput and distributed operation remain unverified. Package installation scans affected existing records; use a maintenance window for large configuration changes. Direct database writes are outside these guarantees.

Primary documentation consulted on 2026-11-14: Australia [data modelling](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/application-development/build-data-model.md), [data policies](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/c_DataPolicy.md), [assignment-group qualification](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/platform-administration/t_ExJSConstrainAssignmentGroupField.md), and [configuration publication](https://raw.githubusercontent.com/ServiceNow/ServiceNowDocs/australia/markdown/application-development/t_PublishApplicationsToAnUpdateSet.md). These establish capability counterparts, not identical local API semantics. Product/licence availability and licensed-instance conformance have not been independently tested.

State-entry `roles` apply to creation in the initial state as well as later state changes. Omitting `state` does not bypass its initial policy. Administrator migration imports remain the separately documented ingestion path.

### Field write roles

A field rule may include `write_roles`, a unique list containing `admin` and/or `agent`. Omission preserves existing mutation permissions; an empty list denies value changes by every role. For example, `{"type":"decimal","write_roles":["admin"]}` lets agents read a value but reserves changes for administrators. There is no implicit administrator override. Create, update, browser form and configured-table import paths enforce the policy. Import permission failures become row errors and preserve atomic import behavior.

Submitting the stored value unchanged is permitted, so full forms can save edits to other fields. Creation compares against the engine's initial blank/state/priority defaults; imports compare against blank values. Restricted form controls are read-only or disabled, but server checks remain authoritative. This is write authorization only: values remain readable/searchable/exportable under existing record visibility. Field read ACLs, encryption and anonymization remain separate work.

### Table record operations

Optional `record_roles` maps `create`, `update` and `delete` to unique lists of `admin` and/or `agent`. Omitted operations preserve the existing role rules; empty lists deny that operation, including for administrators. These are additional restrictions: they cannot grant readers field mutations or grant agents deletion. For example, `{"create":["admin"],"update":["admin"],"delete":[]}` provides administrator-maintained records protected from deletion.

Server checks cover Table API create/update/delete, record forms and imports (`create` policy, including preview). The workspace and forms hide forbidden mutation controls; forged requests still fail with 403. Read visibility, comments, internal notes, attachments and membership administration retain their separate existing permissions. This policy controls record rows/fields, not all related resources or arbitrary ServiceNow ACL scripts.
