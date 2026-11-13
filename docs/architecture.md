# Foundation architecture

The application is a Python/Flask server with server-rendered Jinja views and CSS. This keeps the first workspace fast, accessible without client-side JavaScript and easy to package. Both the UI and HTTP APIs call the same validation, workflow and storage services.

PostgreSQL is the deployment database. SQLAlchemy provides transaction boundaries and bound query parameters; SQLite is only a fast local test/development alternative. JSON record values are shaped by a persisted schema registry. Separate relational tables retain users, audit events and attachment bytes. Attachments in PostgreSQL avoid ephemeral-container data loss and inconsistent local file storage across the three cloud targets; the current limit is 5 MB each.

`catalog.py` owns initial table and workflow defaults. `store.py` owns physical storage. `service.py` owns record operations, query construction and migration. `app.py` owns HTTP authentication, CSRF and presentation. The `os_*` tables are internal implementation details, not ServiceNow metadata-table compatibility.

Each request has its own session. Mutation services operate in one transaction; failed requests close without committing. PostgreSQL record updates acquire row locks. Local revision ETags make stale-edit conflicts visible. Initial schema creation and the admin bootstrap happen at application startup; each cloud environment is capped at one application instance until migration leadership and coordinated schema upgrades exist.

This is a single-tenant deployment. Role checks control action categories, not individual records or fields. Audit history survives record deletion. Internal notes are visible to all authenticated roles because end-user accounts and private/public comment semantics have not yet been introduced.

The application has no telemetry, third-party browser fonts, external scripts or AI provider dependency. Outbound email and integration jobs are future modules. Terraform is split into three independently initialized root configurations; there is no shared cloud state or automatic cross-cloud failover.
