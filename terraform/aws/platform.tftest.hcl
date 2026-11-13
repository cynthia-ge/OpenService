mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = { names = ["eu-west-1a", "eu-west-1b"] }
  }
}
mock_provider "random" {}
run "secure_foundation" {
  command = plan
  variables { image = "public.ecr.aws/example/openservice:0.1.0" }
  assert {
    condition     = aws_db_instance.app.publicly_accessible == false && aws_db_instance.app.storage_encrypted && aws_db_instance.app.deletion_protection
    error_message = "Database must remain private, encrypted and protected."
  }
  assert {
    condition     = aws_apprunner_service.app.health_check_configuration[0].path == "/healthz" && aws_apprunner_auto_scaling_configuration_version.app.max_size == 1
    error_message = "Use health checks and a single initial instance."
  }
}
run "reject_unpinned_image" {
  command = plan
  variables { image = "public.ecr.aws/example/openservice:latest" }
  expect_failures = [var.image]
}
