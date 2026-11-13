# Foundation validation — 13 November 2026

- **62 tests pass** against isolated SQLite databases.
- **The same 62 tests pass against PostgreSQL 17** inside Docker, using temporary schemas that are dropped after each test. The demo database tables remain intact.
- Python application and credential-helper coverage: **468/468 executable lines and 162/162 branches**. The package initializer contains only a docstring (zero executable lines). No coverage exclusions or ignore annotations were added.
- The bundled `open-service` exact-count checker passes for `app.py`, `catalog.py`, `service.py`, `store.py` and `scripts/setup-local.py`.
- **Chromium journey passes:** session login, create an incident, transition to in-progress, add a note, preview an import, verify no mobile document overflow. Screenshots were visually inspected for desktop and mobile; the mobile table scrolls horizontally to preserve legible columns.
- Docker images build successfully. App and PostgreSQL health checks pass. Demonstration records persist across application container replacement.
- All three Terraform roots pass `terraform validate`. **Six mock-provider plan tests pass** (two per cloud), including protected databases and rejection of `:latest` images. Provider lockfiles include Darwin ARM64 and Linux AMD64 checksums.
- Secrets and local state are ignored. The setup helper tests private file permissions, unique credentials, secret-free output and preservation on rerun.

Python line coverage does not measure CSS/template coverage. UI checks verify the rendered journeys, not pixel identity with ServiceNow. Terraform mock tests do not prove live cloud provisioning. This is not an independent ServiceNow conformance test, a load test or a penetration test.

Reproduction commands are in the root README and Terraform guide. The compatibility ledger records remaining product and enterprise-operation gaps.

## Count aggregation iteration

The expanded suite passes **78 tests on both SQLite and PostgreSQL 17**. Measured production application plus setup-helper coverage is **508/508 lines and 176/176 branches**. The new aggregate module is **38/38 lines and 14/14 branches**. Acceptance first failed with 404 on the baseline. Tests cover empty and filtered counts, multicolumn grouping, ordering, permissions, unsupported parameters and the absence of pagination truncation. PostgreSQL verification: `docker compose --profile test build test` and `docker compose --profile test run --rm test`. Earlier browser and Terraform results above belong to the foundation iteration; this API-only iteration does not change those surfaces.

## Membership filter continuation

**88 tests pass on both SQLite and PostgreSQL 17.** Coverage is **515/515 lines and 180/180 branches** including setup-local.py; runtime only is 499/499 lines and 178/178 branches. The touched service.py has 160/160 lines and 76/76 branches; the bundled exact-count gate and named-function checks pass. PostgreSQL was verified with the Docker test target against a fresh task-owned database on a temporary network; both were removed afterward. Existing demo data was untouched. The ten new cases cover membership through Table and Aggregate APIs, full-selection counts, grouping/paging, literal values, limits, auth and invalid queries. Earlier browser/Terraform measurements above remain historical; this iteration changes only backend query behavior.
