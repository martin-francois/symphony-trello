# The personal API key comes from the POSTHOG_API_KEY environment variable, which
# scripts/posthog-infra sets for the tofu process only. Nothing here names an existing project:
# every project is created by this configuration.
provider "posthog" {
  host            = var.posthog_host
  organization_id = var.organization_id
}
