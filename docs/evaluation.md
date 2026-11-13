# First evaluation

Use a local disposable instance and synthetic records for this walkthrough. It exercises individual sign-in, service-desk work and read-only review. OpenService is an evaluation foundation; the [compatibility ledger](servicenow-compatibility.md) records what remains before production adoption.

## Evaluation boundary

This walkthrough is milestone M0 in the [adoption roadmap](roadmap.md#milestones-and-exit-gates). It is suitable for feature feedback on synthetic data. It does not establish a self-service onboarding flow, safely scoped multi-person operation or readiness for real customer/employee data. Before offering a managed shared evaluation, complete M1 identity/invite/revocation/access/delivery gates; before an operational pilot, record the evaluator-specific requirements and satisfy M2. The existing manual account steps below remain the current behavior, not the target onboarding design.

## Start the workspace

Follow [Run locally](../README.md#run-locally), then open http://localhost:8090 and sign in as `admin` with the generated password from your private `.env`. The default listener is local to your computer. Each evaluator can run their own copy; a shared hosted instance needs separately configured access and HTTPS.

## Give each person their own login

1. As administrator, choose **Accounts** in the navigation.
2. Create an account such as `EvalAgent`, choose **Agent**, and supply a unique password of 12–1024 characters. Usernames accept 1–80 letters or digits and are case-sensitive.
3. Create `EvalReader` with **Reader**, the form's default role. Share credentials through an agreed secure channel.
4. Confirm both names and roles appear in the list. With more than 25 accounts, use the page controls. Passwords are never displayed after submission.

Administrator can also create another administrator. Duplicate names return an inline error without changing the existing account. Select an account name to deactivate or reactivate it. Deactivation blocks browser and Basic authentication, and existing browser sessions lose access on their next request; reactivation requires a fresh login. The last active administrator cannot be deactivated. Use the account’s role form to promote or demote it; existing browser sessions must sign in again. Each user can change their own password from Account security using their current password. Forgotten-password recovery remains unavailable in this slice, so keep the environment disposable.

## Walk through service-desk work

| Person | Try | Expected result |
|---|---|---|
| Agent, in a separate browser profile | Sign in and open Incidents; create a record with a synthetic summary | A persistent incident with its own number appears |
| Agent | Move the incident from New to In progress, save and add an internal note | The new state and activity appear after reloading |
| Reader, in another profile | Sign in and open the same incident | The record and activity are visible; creation/edit controls are unavailable |
| Reader or agent | Open `/admin/accounts` directly | Access is denied; the Accounts navigation link is absent |
| Administrator | Return to Accounts | All local accounts and their roles are visible |

All three roles can read all workspace records, notes and attachments. Agent can create/update records, add notes and upload files; administrator additionally manages account creation, imports data, approves changes and deletes records. These are action permissions, not team, customer or field isolation.

For a broader review, use [Migration studio](migration.md) to preview a small synthetic export before applying it, inspect the audit trail and compare the [API scope](api.md) with required integrations. Keep notes on the workflow, expected behavior, observed result and any missing capability.

## Adoption questions to capture

- Which service workflows, integrations and migration sources are essential?
- What identity provider, team/customer separation and audit controls are required?
- What availability, recovery, upgrade and performance targets would a pilot need?

SSO, granular ACLs/domain isolation, password reset, comprehensive lifecycle controls and high availability remain incomplete; versioned upgrades now exist but operational restore drills remain unverified. Existing Docker/browser tests demonstrate the documented evaluation workflows; they do not establish production readiness or complete ServiceNow compatibility.

Use **Security events** as an administrator to review successful account changes. Failed attempts are not part of this journal; it shows the latest 100 recorded lifecycle events and does not replace external security monitoring.
