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

## Account onboarding iteration

**112 tests pass on SQLite and PostgreSQL 17**, including 24 new account cases. The existing API and new browser route share validation and persistence. Tests cover authorization/CSRF, successful sign-in, hashes, validation/type/length boundaries, duplicate safety, rollback after a real unique-constraint failure, listing pagination and rejection of invalid parameters. Two initial regression cases returned 404 before implementation.

The final production scope is `src/openservice/accounts.py`, `src/openservice/app.py`, `src/openservice/templates/accounts.html`, `src/openservice/templates/base.html` and `src/openservice/static/app.css`. Fresh coverage was measured after erasing stale data; the bundled exact-count gate checks every executable Python production file in the report. Named functions also have no missing lines or branches.

| Measured Python scope | Executable lines | Branches | Named functions |
|---|---:|---:|---:|
| New `accounts.py` | 41/41 | 16/16 | 2/2 |
| Modified `app.py`, whole file | 248/248 | 80/80 | 24/24 |
| Touched Python total | 289/289 | 96/96 | 26/26 |
| All runtime Python | 550/550 | 192/192 | 41/41 |
| Runtime plus existing setup helper | 566/566 | 194/194 | 42/42 |

`__init__.py` has zero executable lines. Python coverage does not instrument templates or CSS. Chromium tests the rendered form, role default, creation, duplicate error, password clearing, new-account sign-in, agent record updates, reader restrictions and denied administration access. Desktop (1512×1100) and mobile (390×844) captures are visually reviewed; the mobile document has no horizontal overflow. The browser check caught and prompted correction of labels that included help text/select options. There are no client-side scripts in this feature.

Docker runtime/test images build successfully. PostgreSQL tests and browser checks use a fresh task-owned database and application with generated credentials on a disposable Docker network. Those resources are removed afterward; the existing demo database is untouched. No cloud deployment or vendor instance is involved. Earlier Terraform results above remain historical because this iteration does not change infrastructure.

Reproduce the Python measurement and exact-count gate from the product directory (the skill path assumes the Cynthia workspace layout):

```bash
.venv/bin/python -m coverage erase
.venv/bin/python -m coverage run -m pytest -q
.venv/bin/python -m coverage json
.venv/bin/python ../skills/open-service/scripts/check_coverage.py \
  --report coverage.json \
  --file src/openservice/accounts.py --file src/openservice/app.py \
  --file src/openservice/aggregate.py --file src/openservice/catalog.py \
  --file src/openservice/service.py --file src/openservice/store.py \
  --file scripts/setup-local.py
```

Run PostgreSQL acceptance through the README's Docker test commands. To reproduce the browser journey, install Chromium, set `OPEN_SERVICE_EVAL_URL` and `OPEN_SERVICE_EVAL_PASSWORD` for an isolated instance with the `admin` account, then run `.venv/bin/python tests/accounts_browser_journey.py`. It creates two synthetic users and an incident and writes ignored `artifacts/accounts-desktop.png` and `artifacts/accounts-mobile.png`. It intentionally leaves those sample records in that evaluation instance; use a disposable database for a clean rerun.
