terraform {

  required_version = ">= 1.10, < 2.0"
  required_providers {

    google = {
      source = "hashicorp/google", version = "~> 7.0"
    }
    random = {
      source = "hashicorp/random", version = "~> 3.7"
    }

  }

}
provider "google" {
  project = var.project_id
  region  = var.region

}
resource "google_project_service" "apis" {

  for_each           = toset(["run.googleapis.com", "sqladmin.googleapis.com", "secretmanager.googleapis.com", "iam.googleapis.com"])
  service            = each.key
  disable_on_destroy = false

}
resource "random_password" "database" {
  length  = 32
  special = false

}
resource "random_password" "session" {
  length  = 64
  special = false

}
resource "random_password" "admin" {
  length  = 32
  special = false

}
resource "google_service_account" "app" {
  account_id   = var.name
  display_name = "OpenService runtime"
  depends_on   = [google_project_service.apis]

}
resource "google_project_iam_member" "sql" {

  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.app.email}"

}
resource "google_sql_database_instance" "app" {

  name                = var.name
  region              = var.region
  database_version    = "POSTGRES_17"
  deletion_protection = true
  settings {

    tier            = "db-custom-1-3840"
    edition         = "ENTERPRISE"
    disk_autoresize = true
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true

    }
    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"

    }

  }
  depends_on = [google_project_service.apis]

}
resource "google_sql_database" "app" {
  name     = "openservice"
  instance = google_sql_database_instance.app.name

}
resource "google_sql_user" "app" {
  name     = "openservice"
  instance = google_sql_database_instance.app.name
  password = random_password.database.result

}
locals {

  secrets = {

    DATABASE_URL   = "postgresql+psycopg://openservice:${random_password.database.result}@/openservice?host=/cloudsql/${google_sql_database_instance.app.connection_name}"
    SECRET_KEY     = random_password.session.result
    ADMIN_PASSWORD = random_password.admin.result

  }

}
resource "google_secret_manager_secret" "app" {

  for_each  = toset(["DATABASE_URL", "SECRET_KEY", "ADMIN_PASSWORD"])
  secret_id = "${var.name}-${lower(replace(each.key, "_", "-"))}"
  replication {
    auto {

    }
  }
  depends_on = [google_project_service.apis]

}
resource "google_secret_manager_secret_version" "app" {

  for_each    = google_secret_manager_secret.app
  secret      = each.value.id
  secret_data = local.secrets[each.key]

}
resource "google_secret_manager_secret_iam_member" "app" {

  for_each  = google_secret_manager_secret.app
  secret_id = each.value.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app.email}"

}
resource "google_cloud_run_v2_service" "app" {

  name                = var.name
  location            = var.region
  deletion_protection = true
  ingress             = "INGRESS_TRAFFIC_ALL"
  template {

    service_account = google_service_account.app.email
    scaling {
      min_instance_count = 1
      max_instance_count = 1

    }
    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [google_sql_database_instance.app.connection_name]
      }

    }
    containers {

      image = var.image
      ports {
        container_port = 8080
      }
      resources {
        limits = {
          cpu = "1", memory = "1Gi"
        }
      }
      volume_mounts {
        name       = "cloudsql"
        mount_path = "/cloudsql"

      }
      env {
        name  = "COOKIE_SECURE"
        value = "true"

      }
      dynamic "env" {

        for_each = google_secret_manager_secret.app
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = env.value.secret_id
              version = google_secret_manager_secret_version.app[env.key].version

            }
          }

        }

      }
      startup_probe {
        initial_delay_seconds = 10
        period_seconds        = 10
        failure_threshold     = 12
        http_get {
          path = "/healthz"
        }

      }

    }

  }
  depends_on = [google_secret_manager_secret_iam_member.app, google_project_iam_member.sql, google_sql_database.app, google_sql_user.app]

}
resource "google_cloud_run_v2_service_iam_member" "public" {

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.app.name
  role     = "roles/run.invoker"
  member   = "allUsers"

}
output "url" {
  value = google_cloud_run_v2_service.app.uri
}
output "admin_secret_id" {
  value = google_secret_manager_secret.app["ADMIN_PASSWORD"].secret_id
}
