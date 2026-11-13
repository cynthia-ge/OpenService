# OpenService cloud deployments

Three independent Terraform roots deploy the same application image and a persistent PostgreSQL database. Choose one; applying all three creates three separate, billable installations. These configurations have been validated and tested with mocked providers, **not applied to live accounts**.

| Root | Application | Database and network | Credentials |
|---|---|---|---|
| `aws` | App Runner, managed HTTPS | RDS PostgreSQL 17 in private subnets; VPC connector | Secrets Manager and scoped runtime IAM |
| `gcp` | Cloud Run, managed HTTPS | Cloud SQL PostgreSQL 17; authenticated connector/socket, no authorized public client networks | Secret Manager and service-account IAM |
| `azure` | Container Apps, managed HTTPS | PostgreSQL Flexible Server 17 in a delegated private subnet | Container Apps encrypted secrets |

All roots set a single initial application instance, health checks, database backups and deletion protections. These are cost-conscious foundations, not high-availability production architectures. AWS VPC egress permits database traffic only; future external integrations need a separately designed egress path. Region/SKU availability, organization policies, quotas and registry access require verification in the selected account.

## 1. Publish an application image

Use a registry accessible to the selected cloud. Publishing is a deliberate separate operation; Terraform does not build or push code.

```bash
# Replace with your actual registry path and immutable release tag.
docker buildx build --platform linux/amd64 --target runtime \
  -t YOUR_REGISTRY/openservice:0.1.0 --push .
```

AWS currently expects a **public ECR** image. GCP accepts an image accessible to Cloud Run (for private cross-project Artifact Registry, configure pull permissions first). Azure currently expects a publicly pullable image. Private ECR/ACR authentication is not implemented in these roots. Never bake `.env` or other credentials into the image; `.dockerignore` excludes them.

## 2. Authenticate to the intended account

Do not rely on whichever default account happens to be active.

**AWS:** use your named AWS profile, then verify it:

```bash
aws login --profile YOUR_LOGIN_PROFILE
aws sts get-caller-identity --profile YOUR_TERRAFORM_PROFILE
```

Set `profile` in the AWS tfvars to the profile your existing credential setup exposes to Terraform. These files do not change your `~/.aws` configuration. IAM Identity Center users can use `aws sso login --profile ...` instead.

**GCP:**

```bash
gcloud auth login
gcloud auth application-default login
gcloud config set project YOUR_PROJECT_ID
```

The project must already exist with billing enabled. The deployment identity needs permission to enable APIs, provision Cloud SQL/Run, create service accounts and bind the required IAM policies. Public invocation may be prohibited by your organization; adapt the front-door IAM policy if so.

**Azure:**

```bash
az login
az account set --subscription YOUR_SUBSCRIPTION_ID
az account show
```

Set the explicit subscription ID in tfvars. Provider registration and resource creation permissions are required.

## 3. Configure state before the first apply

Copy the selected `backend.tf.example` to `backend.tf`, replace its placeholders, and use an existing dedicated backend storage resource. The examples do not bootstrap state infrastructure. Use encryption, versioning, access controls and the backend's locking mechanism. Keep state separate per environment/cloud.

Terraform state contains generated database, bootstrap-admin and session secrets even though they are not ordinary outputs. Do not commit state, plans or actual tfvars. Local state is suitable only for isolated evaluation and must be protected; migrate it to the secured backend before shared operations.

## 4. Plan and deploy one target

From the OpenService repository root:

```bash
cp terraform/aws/terraform.tfvars.example terraform/aws/terraform.tfvars
# Edit the profile, region and image in terraform.tfvars.
terraform -chdir=terraform/aws init
terraform -chdir=terraform/aws plan -out=deployment.tfplan
terraform -chdir=terraform/aws apply deployment.tfplan
terraform -chdir=terraform/aws output -raw url
```

Replace `aws` with `gcp` or `azure` for the other configurations. Lockfiles are checked in. Custom domains, DNS, WAF, SSO, HA and backup restore drills are not included.

## 5. Retrieve the bootstrap password

Username is `admin`. Retrieve the generated password through the selected cloud's authenticated console or CLI, not from source control:

- AWS: output `admin_secret_arn`, then use Secrets Manager's secret-value view.
- GCP: output `admin_secret_id`, then access the current Secret Manager version.
- Azure: output `resource_group` and `container_app`, then use Container Apps' secrets management. The CLI command `az containerapp secret list --show-values` exposes secrets; use it only in a trusted terminal.

The bootstrap password creates the first user only; rotating the environment secret does not change an existing stored password. Application password rotation/account recovery is an outstanding feature. Do not promote this foundation to production before adding identity lifecycle controls and the security features recorded in the product ledger.

## Verification without cloud changes

```bash
terraform -chdir=terraform/aws init -backend=false
terraform -chdir=terraform/aws validate
terraform -chdir=terraform/aws test
```

Repeat for GCP and Azure. Tests mock all providers and assert database protection, scaling/HTTPS settings and rejection of `:latest` images. They cannot prove cloud provisioning or application connectivity.

## Updates and teardown

Back up the database before changing the application image. The current initializer creates missing physical tables but does not migrate existing column definitions. Plan schema changes explicitly. Secret rotation may require a new application revision; an application restart alone does not rotate stored login passwords.

Destruction is intentionally blocked for database resources. To remove a test environment, first export/back up its data, explicitly remove the relevant deletion protection, review a destroy plan and confirm what is being deleted. AWS retains a final snapshot; its name must remain unique on repeated destroy/recreate cycles. Azure's `prevent_destroy` must be changed deliberately. Terraform state-backend resources are managed separately and should be retained according to your retention policy.
