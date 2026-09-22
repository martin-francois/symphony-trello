terraform {
  required_version = "~> 1.12.0"

  required_providers {
    posthog = {
      source  = "PostHog/posthog"
      version = "1.0.21"
    }
  }

  # The state path is supplied at init time by scripts/posthog-infra so it stays outside the
  # repository; see docs/posthog-infrastructure.md.
  backend "local" {}
}
