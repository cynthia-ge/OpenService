terraform {

  required_version = ">= 1.10, < 2.0"
  required_providers {

    azurerm = {
      source = "hashicorp/azurerm", version = "~> 4.0"
    }
    random = {
      source = "hashicorp/random", version = "~> 3.7"
    }

  }

}
provider "azurerm" {
  features {

  }
  subscription_id = var.subscription_id

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
resource "random_id" "suffix" {
  byte_length = 4
}
resource "azurerm_resource_group" "app" {
  name     = "${var.name}-rg"
  location = var.location
  tags = {
    Project = "OpenService", ManagedBy = "Terraform"
  }

}
resource "azurerm_virtual_network" "app" {

  name                = "${var.name}-vnet"
  location            = var.location
  resource_group_name = azurerm_resource_group.app.name
  address_space       = ["10.43.0.0/16"]

}
resource "azurerm_subnet" "app" {

  name                 = "containers"
  resource_group_name  = azurerm_resource_group.app.name
  virtual_network_name = azurerm_virtual_network.app.name
  address_prefixes     = ["10.43.0.0/23"]
  delegation {
    name = "containerapps"
    service_delegation {
      name    = "Microsoft.App/environments"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]

    }

  }

}
resource "azurerm_subnet" "db" {

  name                 = "database"
  resource_group_name  = azurerm_resource_group.app.name
  virtual_network_name = azurerm_virtual_network.app.name
  address_prefixes     = ["10.43.2.0/24"]
  delegation {
    name = "postgresql"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]

    }

  }

}
resource "azurerm_private_dns_zone" "db" {
  name                = "${var.name}.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.app.name

}
resource "azurerm_private_dns_zone_virtual_network_link" "db" {

  name                  = "postgresql"
  resource_group_name   = azurerm_resource_group.app.name
  private_dns_zone_name = azurerm_private_dns_zone.db.name
  virtual_network_id    = azurerm_virtual_network.app.id

}
resource "azurerm_postgresql_flexible_server" "app" {

  name                          = "${var.name}-${random_id.suffix.hex}"
  resource_group_name           = azurerm_resource_group.app.name
  location                      = var.location
  version                       = "17"
  delegated_subnet_id           = azurerm_subnet.db.id
  private_dns_zone_id           = azurerm_private_dns_zone.db.id
  public_network_access_enabled = false
  administrator_login           = "openservice"
  administrator_password        = random_password.database.result
  sku_name                      = "B_Standard_B1ms"
  storage_mb                    = 32768
  backup_retention_days         = 7
  lifecycle {
    prevent_destroy = true
  }
  depends_on = [azurerm_private_dns_zone_virtual_network_link.db]

}
resource "azurerm_postgresql_flexible_server_database" "app" {

  name      = "openservice"
  server_id = azurerm_postgresql_flexible_server.app.id
  charset   = "UTF8"
  collation = "en_US.utf8"

}
resource "azurerm_log_analytics_workspace" "app" {

  name                = "${var.name}-logs"
  location            = var.location
  resource_group_name = azurerm_resource_group.app.name
  sku                 = "PerGB2018"
  retention_in_days   = 30

}
resource "azurerm_container_app_environment" "app" {

  name                       = "${var.name}-environment"
  location                   = var.location
  resource_group_name        = azurerm_resource_group.app.name
  log_analytics_workspace_id = azurerm_log_analytics_workspace.app.id
  infrastructure_subnet_id   = azurerm_subnet.app.id
  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"

  }

}
resource "azurerm_container_app" "app" {

  name                         = var.name
  resource_group_name          = azurerm_resource_group.app.name
  container_app_environment_id = azurerm_container_app_environment.app.id
  revision_mode                = "Single"
  secret {
    name  = "database-url"
    value = "postgresql+psycopg://openservice:${random_password.database.result}@${azurerm_postgresql_flexible_server.app.fqdn}/openservice?sslmode=require"

  }
  secret {
    name  = "session-key"
    value = random_password.session.result

  }
  secret {
    name  = "admin-password"
    value = random_password.admin.result

  }
  ingress {
    external_enabled           = true
    target_port                = 8080
    allow_insecure_connections = false
    traffic_weight {
      latest_revision = true
      percentage      = 100

    }

  }
  template {

    min_replicas = 1
    max_replicas = 1
    container {

      name   = "openservice"
      image  = var.image
      cpu    = 0.5
      memory = "1Gi"
      env {
        name        = "DATABASE_URL"
        secret_name = "database-url"

      }
      env {
        name        = "SECRET_KEY"
        secret_name = "session-key"

      }
      env {
        name        = "ADMIN_PASSWORD"
        secret_name = "admin-password"

      }
      env {
        name  = "COOKIE_SECURE"
        value = "true"

      }
      readiness_probe {
        transport        = "HTTP"
        port             = 8080
        path             = "/healthz"
        interval_seconds = 10

      }
      startup_probe {
        transport               = "HTTP"
        port                    = 8080
        path                    = "/healthz"
        interval_seconds        = 10
        failure_count_threshold = 20

      }

    }

  }
  depends_on = [azurerm_postgresql_flexible_server_database.app]

}
output "url" {
  value = "https://${azurerm_container_app.app.ingress[0].fqdn}"
}
output "resource_group" {
  value = azurerm_resource_group.app.name
}
output "container_app" {
  value = azurerm_container_app.app.name
}
