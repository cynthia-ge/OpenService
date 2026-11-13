mock_provider "azurerm" {
  mock_resource "azurerm_virtual_network" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.Network/virtualNetworks/openservice-vnet" } }
  mock_resource "azurerm_subnet" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.Network/virtualNetworks/openservice-vnet/subnets/database" } }
  mock_resource "azurerm_private_dns_zone" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.Network/privateDnsZones/openservice.postgres.database.azure.com" } }
  mock_resource "azurerm_postgresql_flexible_server" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.DBforPostgreSQL/flexibleServers/openservice" } }
  mock_resource "azurerm_log_analytics_workspace" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.OperationalInsights/workspaces/openservice-logs" } }
  mock_resource "azurerm_container_app_environment" { defaults = { id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/openservice-rg/providers/Microsoft.App/managedEnvironments/openservice-environment" } }
}
mock_provider "random" {}
run "secure_foundation" {
  command = plan
  variables {
    subscription_id = "00000000-0000-0000-0000-000000000000"
    image           = "docker.io/example/openservice:0.1.0"
  }
  assert {
    condition     = !azurerm_postgresql_flexible_server.app.public_network_access_enabled && azurerm_postgresql_flexible_server.app.backup_retention_days >= 7
    error_message = "Database must be private with backups."
  }
  assert {
    condition     = !azurerm_container_app.app.ingress[0].allow_insecure_connections && azurerm_container_app.app.template[0].max_replicas == 1
    error_message = "Use HTTPS and one initial replica."
  }
}
run "reject_unpinned_image" {
  command = plan
  variables {
    subscription_id = "00000000-0000-0000-0000-000000000000"
    image           = "docker.io/example/openservice:latest"
  }
  expect_failures = [var.image]
}
