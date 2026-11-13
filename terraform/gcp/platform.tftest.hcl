mock_provider "google" {}
mock_provider "random" {}
run "secure_foundation" {
  command = plan
  variables {
    project_id = "openservice-example"
    image      = "europe-west1-docker.pkg.dev/openservice-example/apps/openservice:0.1.0"
  }
  assert {
    condition     = google_sql_database_instance.app.deletion_protection && google_sql_database_instance.app.settings[0].backup_configuration[0].point_in_time_recovery_enabled
    error_message = "Database protection and point-in-time recovery are required."
  }
  assert {
    condition     = google_cloud_run_v2_service.app.template[0].scaling[0].max_instance_count == 1
    error_message = "Start with one instance until coordinated migrations are implemented."
  }
}
run "reject_unpinned_image" {
  command = plan
  variables {
    project_id = "openservice-example"
    image      = "docker.io/example/openservice:latest"
  }
  expect_failures = [var.image]
}
