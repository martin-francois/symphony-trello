variable "erasure_enabled" {
  description = "Enable native erasure only after the hosted lifecycle gate passes for this role."
  type        = bool
  default     = false
}

variable "erasure_secrets" {
  description = "Versioned HMAC masters and a project-scoped management credential. Keep old key versions and an operator backup."
  sensitive   = true
  type = object({
    active_key  = string
    master_keys = map(string)
    api_key     = string
  })
  default = { active_key = "k1", master_keys = {}, api_key = "" }
}

locals {
  erasure_scope = "symphony:${posthog_project.this.id}"
  erasure_hog = templatefile("${path.module}/../../erasure-service.hog.tftpl", {
    scope                 = local.erasure_scope
    project               = posthog_project.this.id
    active_key            = var.erasure_secrets.active_key
    master_key_references = join(", ", [for version in keys(var.erasure_secrets.master_keys) : "'${version}': inputs.master_${version}"])
  })
}

resource "posthog_hog_function" "merge_filter" {
  count           = var.erasure_enabled ? 1 : 0
  project_id      = tostring(posthog_project.this.id)
  type            = "transformation"
  name            = "Symphony identity merge filter"
  description     = "Drops identity-changing events. This is a mitigation, not a fail-closed security boundary."
  hog             = file("${path.module}/../../merge-filter.hog")
  filters_json    = jsonencode({ source = "events" })
  execution_order = 0
  enabled         = true
}

resource "posthog_hog_function" "erasure" {
  count        = var.erasure_enabled ? 1 : 0
  project_id   = tostring(posthog_project.this.id)
  type         = "source_webhook"
  name         = "Symphony authenticated erasure"
  description  = "HMAC ownership, immutable reporting periods and provider-verified erasure status."
  hog          = local.erasure_hog
  enabled      = true
  filters_json = jsonencode({})
  inputs_schema_json = jsonencode(concat(
    [{ key = "api_key", type = "string", secret = true, required = true }],
    [for version in keys(var.erasure_secrets.master_keys) : { key = "master_${version}", type = "string", secret = true, required = true }]
  ))
  sensitive_inputs_json = jsonencode(merge(
    { api_key = { value = var.erasure_secrets.api_key } },
    { for version, key in var.erasure_secrets.master_keys : "master_${version}" => { value = key } }
  ))

  lifecycle {
    precondition {
      condition = (
        can(regex("^phx_", var.erasure_secrets.api_key)) &&
        contains(keys(var.erasure_secrets.master_keys), var.erasure_secrets.active_key) &&
        alltrue([for version, key in var.erasure_secrets.master_keys : can(regex("^k[1-9][0-9]{0,3}$", version)) && can(regex("^[0-9a-f]{64}$", key))])
      )
      error_message = "Erasure requires a scoped API key, an active master version and 256-bit hex keys."
    }
  }
}
