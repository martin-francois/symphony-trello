output "projects" {
  description = "Generated identifiers per role, consumed by scripts/posthog-infra for the API supplements and the verification report."
  value = {
    production = module.production.project
    test       = module.test.project
  }
}

output "capture_tokens" {
  description = "Public capture tokens per role. Sensitive so they never print; scripts/posthog-infra hands the production token to the release secret."
  value = {
    production = module.production.capture_token
    test       = module.test.capture_token
  }
  sensitive = true
}
