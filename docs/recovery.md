# Disposable PostgreSQL restore drill

Build a local image, then run:

```bash
docker build -t openservice:local .
bash scripts/restore-drill.sh openservice:local
```

Dependencies: Docker, Bash, curl, jq and OpenSSL. The drill uses synthetic data, generated credentials, two fresh PostgreSQL 17 volumes, a private Docker network and ephemeral loopback application ports. It resolves the local application and database images to immutable IDs for the run. Cleanup removes only resources created by that run, including the temporary dump. It does not accept an existing database URL or operate on a deployed instance.

The drill installs the checked-in service operations package, creates an account, linked asset/case records and a binary attachment, then restricts the case to its private owner. After recording configuration, record, security-event and analytics snapshots, it stops the source application and creates a PostgreSQL custom-format dump. It stops the source database, restores into a different fresh database volume and starts a fresh non-root application container. It compares the snapshots and attachment bytes, verifies restored account credentials and checks that the reader still cannot access the private case.

This proves a bounded, quiesced backup/restore path for the exercised schema and data. It does not prove production recovery time/data-loss objectives, backup scheduling/retention/encryption, disaster recovery across clouds, online point-in-time recovery, rolling upgrades or high availability. Configuration/secrets must be recovered by the operator in a real deployment; the drill keeps its generated values only for the duration of the test. Browser sessions are not restored. Infrastructure recovery does not reverse external business actions.

The existing rollout migrations are exercised by application startup. An upgrade across different application/database versions requires a separate rehearsal with the exact source/target versions and an authorized data copy. Keep ADOPT-15/17/18 and OS-PARITY-10 partial until those deployment-specific requirements are evidenced.
