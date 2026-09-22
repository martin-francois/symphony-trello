output "project" {
  description = "Generated identifiers of this project."
  value = {
    id           = posthog_project.this.id
    name         = posthog_project.this.name
    dashboard_id = posthog_dashboard.installations.id
    insights     = { for key, insight in posthog_insight.panel : key => { id = insight.id, short_id = insight.short_id, name = insight.name } }
  }
}

output "capture_token" {
  description = "Public capture token of this project."
  value       = posthog_project.this.api_token
  sensitive   = true
}

output "privacy_policy" {
  description = "The privacy settings this module declares, exported so scripts/posthog-infra verify compares the live project against the definition rather than a second copy."
  value = {
    timezone                      = posthog_project.this.timezone
    anonymize_ips                 = posthog_project_settings.this.anonymize_ips
    cookieless_server_hash_mode   = posthog_project_settings.this.cookieless_server_hash_mode
    session_recording_opt_in      = posthog_project_settings.this.session_recording_opt_in
    capture_performance_opt_in    = posthog_project_settings.this.capture_performance_opt_in
    autocapture_exceptions_opt_in = posthog_project_settings.this.autocapture_exceptions_opt_in
    autocapture_web_vitals_opt_in = posthog_project_settings.this.autocapture_web_vitals_opt_in
    heatmaps_opt_in               = posthog_project_settings.this.heatmaps_opt_in
    surveys_opt_in                = posthog_project_settings.this.surveys_opt_in
    app_urls                      = posthog_project_settings.this.app_urls
    recording_domains             = posthog_project_settings.this.recording_domains
    test_account_filters          = jsondecode(posthog_project_settings.this.test_account_filters)
    erasure_hog_sha256            = var.erasure_enabled ? nonsensitive(sha256(trimspace(local.erasure_hog))) : null
    erasure_function_id           = try(posthog_hog_function.erasure[0].id, null)
    erasure_filter_id             = try(posthog_hog_function.merge_filter[0].id, null)
    erasure_audience              = var.erasure_enabled ? local.erasure_scope : null
    geoip_enabled                 = posthog_hog_function.geoip.enabled
  }
}
