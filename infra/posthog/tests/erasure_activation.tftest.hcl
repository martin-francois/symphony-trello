# Production erasure deploys only with a hosted lifecycle pass for the exact handler template
# (docs/telemetry-erasure-implementation.md). Offline: the provider is mocked and nothing is applied.
mock_provider "posthog" {}

variables {
  repository      = "owner/repository"
  release_version = "1.0.0"
  release_date    = "2026-09-01"
  erasure_secrets = {
    production = { active_key = "k1", master_keys = { k1 = "0000000000000000000000000000000000000000000000000000000000000001" }, api_key = "phx_mock" }
    test       = { active_key = "k1", master_keys = { k1 = "0000000000000000000000000000000000000000000000000000000000000002" }, api_key = "phx_mock" }
  }
}

run "production_without_a_pass_is_refused" {
  command = plan
  variables {
    erasure_enabled = { production = true, test = false }
  }
  expect_failures = [var.erasure_enabled]
}

run "production_with_a_pass_for_another_handler_is_refused" {
  command = plan
  variables {
    erasure_enabled        = { production = true, test = false }
    erasure_lifecycle_pass = "0000000000000000000000000000000000000000000000000000000000000000"
  }
  expect_failures = [var.erasure_enabled]
}

run "production_with_a_pass_for_this_handler_plans" {
  command = plan
  variables {
    erasure_enabled        = { production = true, test = false }
    erasure_lifecycle_pass = filesha256("erasure-service.hog.tftpl")
  }
}

run "the_test_role_needs_no_pass" {
  command = plan
  variables {
    erasure_enabled = { production = false, test = true }
  }
}
