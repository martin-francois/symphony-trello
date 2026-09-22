# One Symphony for Trello PostHog project: the project itself, the privacy settings the provider
# can manage, the server-created GeoIP transformation (adopted, then kept disabled), the
# installation dashboard, and one SQL insight per panel. Settings the provider cannot manage yet
# (autocapture_opt_out, capture_console_log_opt_in, capture_dead_clicks) are applied and checked by
# scripts/posthog-infra; see docs/posthog-infrastructure.md.

locals {
  queries_dir = "${path.module}/../../queries"

  # Panel order is dashboard order. Heights are grid rows; the grid has 12 columns at the `sm`
  # breakpoint and 1 column at `xs`.
  panels = [
    {
      key         = "active-installations"
      name        = "Active installations over 1, 7, and 30 days"
      description = "Installations that sent at least one report in each window. Counts reporting installations, not people."
      width       = 12
      height      = 4
    },
    {
      key         = "latest-version-distribution"
      name        = "Latest version distribution"
      description = "Latest reported app_version per installation active in the last 30 days; null is the unknown bucket (source builds)."
      width       = 6
      height      = 6
    },
    {
      key         = "os-family-and-release-distribution"
      name        = "OS family and release distribution"
      description = "Latest reported os_family, os_release, and linux_distribution per installation active in the last 30 days."
      width       = 6
      height      = 6
    },
    {
      key         = "runtime-architecture-distribution"
      name        = "Runtime architecture distribution"
      description = "Latest reported runtime_arch per installation active in the last 30 days."
      width       = 6
      height      = 6
    },
    {
      key         = "current-board-distribution"
      name        = "Current board distribution"
      description = "Latest connected_board_count per installation in buckets; unknown means the manifest was unreadable in the newest report."
      width       = 6
      height      = 6
    },
    {
      key         = "import-and-create-adoption"
      name        = "Import and create adoption"
      description = "Whether each installation active in the last 30 days has ever imported, created, both, or neither, from the cumulative counters of its latest report."
      width       = 6
      height      = 6
    },
    {
      key         = "registration-cohorts"
      name        = "Registration cohorts"
      description = "Installations by the month of their registered_on, from the latest report in the last 365 days."
      width       = 6
      height      = 6
    },
    {
      key         = "release-adoption-and-delay"
      name        = "Release adoption and delay"
      description = "Per installation: could it have upgraded to the configured release, and how soon was it first observed on it. The first observation is kept even after a later downgrade."
      width       = 12
      height      = 7
    },
    {
      key         = "inactive-installations"
      name        = "Inactive installations"
      description = "Installations seen in the last 365 days that sent nothing in the last 30 days. Not an uninstall count."
      width       = 6
      height      = 4
    },
    {
      key         = "reactivated-installations"
      name        = "Reactivated installations"
      description = "Installations whose report in the last 30 days followed a gap of more than 30 days."
      width       = 6
      height      = 4
    },
  ]

  panel_sql = {
    for panel in local.panels : panel.key => (
      panel.key == "release-adoption-and-delay"
      ? templatefile("${local.queries_dir}/release-adoption-and-delay.sql.tftpl", {
        release_version = var.release_version
        release_date    = var.release_date
      })
      : file("${local.queries_dir}/${panel.key}.sql")
    )
  }

  # Dashboard rows: a full-width panel takes a row alone, half-width panels share one. Row height
  # is the tallest panel in it, so a panel's y is the sum of the heights of the rows above.
  panel_rows = [
    ["active-installations"],
    ["latest-version-distribution", "os-family-and-release-distribution"],
    ["runtime-architecture-distribution", "current-board-distribution"],
    ["import-and-create-adoption", "registration-cohorts"],
    ["release-adoption-and-delay"],
    ["inactive-installations", "reactivated-installations"],
  ]
  panel_by_key = { for panel in local.panels : panel.key => panel }
  row_heights  = [for row in local.panel_rows : max([for key in row : local.panel_by_key[key].height]...)]
  panel_positions = merge([
    for row_index, row in local.panel_rows : {
      for column, key in row : key => {
        x = column * 6
        y = sum(concat([0], slice(local.row_heights, 0, row_index)))
        w = local.panel_by_key[key].width
        h = local.panel_by_key[key].height
      }
    }
  ]...)
}

resource "posthog_project" "this" {
  name            = var.name
  organization_id = var.organization_id
  timezone        = "UTC"
}

# Every capture-side product is off. The application sends one custom event and nothing else;
# these settings keep the project from accepting or expecting browser-side data, and
# anonymize_ips drops the client address at ingestion.
resource "posthog_project_settings" "this" {
  project_id = tostring(posthog_project.this.id)

  anonymize_ips                        = true
  cookieless_server_hash_mode          = 0
  session_recording_opt_in             = false
  capture_performance_opt_in           = false
  autocapture_exceptions_opt_in        = false
  autocapture_web_vitals_opt_in        = false
  heatmaps_opt_in                      = false
  surveys_opt_in                       = false
  app_urls                             = []
  recording_domains                    = []
  test_account_filters                 = "[]"
  test_account_filters_default_checked = false
}

# PostHog creates this transformation, enabled, with every new project. It is adopted into state
# by the bootstrap step of scripts/posthog-infra (a scoped lookup followed by `tofu import`) and
# kept disabled here so no server-side lookup of the client address ever runs. The attributes
# mirror the server-created function exactly so the plan is empty after adoption.
resource "posthog_hog_function" "geoip" {
  project_id      = tostring(posthog_project.this.id)
  type            = "transformation"
  name            = "GeoIP"
  description     = "Adds geoip data to the event"
  template_id     = "template-geoip"
  filters_json    = jsonencode({ source = "events" })
  execution_order = 1
  enabled         = false
}

resource "posthog_dashboard" "installations" {
  project_id  = tostring(posthog_project.this.id)
  name        = "Symphony for Trello installations"
  description = "Installation heartbeats. Queries are version-controlled under infra/posthog/queries of the ${var.repository} repository; change them there first."
  pinned      = true
  tags        = ["telemetry"]
}

resource "posthog_insight" "panel" {
  for_each = { for panel in local.panels : panel.key => panel }

  project_id    = tostring(posthog_project.this.id)
  name          = each.value.name
  description   = each.value.description
  query_sql     = local.panel_sql[each.key]
  dashboard_ids = [posthog_dashboard.installations.id]
  tags          = ["telemetry"]
}

resource "posthog_dashboard_layout" "installations" {
  project_id   = tostring(posthog_project.this.id)
  dashboard_id = posthog_dashboard.installations.id

  tiles = [
    for panel in local.panels : {
      insight_id = posthog_insight.panel[panel.key].id
      layouts_json = jsonencode({
        sm = local.panel_positions[panel.key]
        xs = { x = 0, y = local.panel_positions[panel.key].y, w = 1, h = local.panel_positions[panel.key].h }
      })
      show_description = true
    }
  ]

  depends_on = [posthog_insight.panel]
}
