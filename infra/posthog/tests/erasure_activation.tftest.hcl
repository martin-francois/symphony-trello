# Production erasure does not wait for provider completion. These plans use a mocked provider.
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

run "production_plans_without_a_lifecycle_ledger" {
  command = plan
  variables {
    erasure_enabled = { production = true, test = false }
  }
}

run "the_test_role_needs_no_pass" {
  command = plan
  variables {
    erasure_enabled = { production = false, test = true }
  }
}

run "a_role_without_secrets_gets_the_module_default" {
  command = plan
  variables {
    erasure_enabled = { production = false, test = false }
    erasure_secrets = {}
  }
}

run "enabled_service_without_credentials_is_refused" {
  command = plan
  module {
    source = "./modules/project"
  }
  variables {
    name            = "Symphony credential rejection test"
    organization_id = "@current"
    erasure_enabled = true
    erasure_secrets = null
  }
  expect_failures = [posthog_hog_function.erasure]
}

run "enabled_service_without_its_active_master_is_refused" {
  command = plan
  module {
    source = "./modules/project"
  }
  variables {
    name            = "Symphony key rejection test"
    organization_id = "@current"
    erasure_enabled = true
    erasure_secrets = {
      active_key  = "k2"
      master_keys = { k1 = "0000000000000000000000000000000000000000000000000000000000000001" }
      api_key     = "phx_mock"
    }
  }
  expect_failures = [posthog_hog_function.erasure]
}
