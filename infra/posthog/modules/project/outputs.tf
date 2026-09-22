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
