terraform {

  required_version = ">= 1.10, < 2.0"
  required_providers {

    aws = {
      source = "hashicorp/aws", version = "~> 6.0"
    }
    random = {
      source = "hashicorp/random", version = "~> 3.7"
    }

  }

}
provider "aws" {

  region  = var.region
  profile = var.profile
  default_tags {
    tags = {
      Project = "OpenService", ManagedBy = "Terraform"
    }
  }

}
data "aws_availability_zones" "available" {
  state = "available"
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
resource "aws_vpc" "app" {

  cidr_block           = "10.42.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

}
resource "aws_subnet" "private" {

  count             = 2
  vpc_id            = aws_vpc.app.id
  cidr_block        = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index)
  availability_zone = data.aws_availability_zones.available.names[count.index]

}
resource "aws_security_group" "app" {

  name_prefix = "${var.name}-app-"
  vpc_id      = aws_vpc.app.id
  egress {
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [aws_vpc.app.cidr_block]

  }

}
resource "aws_security_group" "db" {

  name_prefix = "${var.name}-db-"
  vpc_id      = aws_vpc.app.id
  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]

  }

}
resource "aws_db_subnet_group" "app" {
  subnet_ids = aws_subnet.private[*].id
}
resource "aws_db_instance" "app" {

  identifier                 = var.name
  engine                     = "postgres"
  engine_version             = "17"
  instance_class             = "db.t4g.micro"
  allocated_storage          = 20
  max_allocated_storage      = 100
  storage_encrypted          = true
  db_name                    = "openservice"
  username                   = "openservice"
  password                   = random_password.database.result
  db_subnet_group_name       = aws_db_subnet_group.app.name
  vpc_security_group_ids     = [aws_security_group.db.id]
  publicly_accessible        = false
  backup_retention_period    = 7
  deletion_protection        = true
  skip_final_snapshot        = false
  final_snapshot_identifier  = "${var.name}-final"
  auto_minor_version_upgrade = true

}
locals {

  secrets = {

    DATABASE_URL   = "postgresql+psycopg://openservice:${random_password.database.result}@${aws_db_instance.app.address}/openservice?sslmode=require"
    SECRET_KEY     = random_password.session.result
    ADMIN_PASSWORD = random_password.admin.result

  }

}
resource "aws_secretsmanager_secret" "app" {

  for_each                = toset(["DATABASE_URL", "SECRET_KEY", "ADMIN_PASSWORD"])
  name_prefix             = "${var.name}/${each.key}-"
  recovery_window_in_days = 7

}
resource "aws_secretsmanager_secret_version" "app" {

  for_each      = aws_secretsmanager_secret.app
  secret_id     = each.value.id
  secret_string = local.secrets[each.key]

}
resource "aws_iam_role" "app" {

  name_prefix = "${var.name}-runtime-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Principal = {
        Service = "tasks.apprunner.amazonaws.com"
      }, Action = "sts:AssumeRole"
    }]
  })

}
resource "aws_iam_role_policy" "secrets" {

  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17", Statement = [{
      Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [for secret in aws_secretsmanager_secret.app : secret.arn]
    }]
  })

}
resource "aws_apprunner_vpc_connector" "app" {

  vpc_connector_name = var.name
  subnets            = aws_subnet.private[*].id
  security_groups    = [aws_security_group.app.id]

}
resource "aws_apprunner_auto_scaling_configuration_version" "app" {

  auto_scaling_configuration_name = var.name
  min_size                        = 1
  max_size                        = 1
  max_concurrency                 = 50

}
resource "aws_apprunner_service" "app" {

  service_name                   = var.name
  auto_scaling_configuration_arn = aws_apprunner_auto_scaling_configuration_version.app.arn
  source_configuration {

    auto_deployments_enabled = false
    image_repository {

      image_identifier      = var.image
      image_repository_type = "ECR_PUBLIC"
      image_configuration {

        port = "8080"
        runtime_environment_variables = {
          COOKIE_SECURE = "true"
        }
        runtime_environment_secrets = {
          for key, secret in aws_secretsmanager_secret.app : key => secret.arn
        }

      }

    }

  }
  instance_configuration {
    cpu               = "1024"
    memory            = "2048"
    instance_role_arn = aws_iam_role.app.arn

  }
  network_configuration {

    egress_configuration {
      egress_type       = "VPC"
      vpc_connector_arn = aws_apprunner_vpc_connector.app.arn

    }

  }
  health_check_configuration {
    protocol            = "HTTP"
    path                = "/healthz"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 1
    unhealthy_threshold = 5

  }
  depends_on = [aws_secretsmanager_secret_version.app, aws_iam_role_policy.secrets]

}
output "url" {
  value = "https://${aws_apprunner_service.app.service_url}"
}
output "admin_secret_arn" {
  value = aws_secretsmanager_secret.app["ADMIN_PASSWORD"].arn
}
