# One module call per logical role. The roles differ only in name; the module carries the
# privacy configuration, the dashboard, and the panels, so test and production cannot drift apart.
module "production" {
  source = "./modules/project"

  name            = var.project_names.production
  organization_id = var.organization_id
  repository      = var.repository
  release_version = var.release_version
  release_date    = var.release_date
}

module "test" {
  source = "./modules/project"

  name            = var.project_names.test
  organization_id = var.organization_id
  repository      = var.repository
  release_version = var.release_version
  release_date    = var.release_date
}
