variable "posthog_host" {
  description = "Management API host of the PostHog region the organization lives in."
  type        = string
  default     = "https://eu.posthog.com"
}

variable "organization_id" {
  description = "Organization that owns the projects: a UUID, a slug, or `@current` for the key owner's organization."
  type        = string
  default     = "@current"
}

variable "project_names" {
  description = "Display names per logical role. Both roles get the same privacy configuration."
  type = object({
    production = string
    test       = string
  })
  default = {
    production = "Symphony for Trello"
    test       = "Symphony for Trello (test)"
  }
}

variable "repository" {
  description = "GitHub repository (owner/name) that holds the canonical queries; named in the dashboard description."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$", var.repository))
    error_message = "repository must be owner/name."
  }
}

variable "release_version" {
  description = "Exact app_version string the release-adoption panel measures."
  type        = string
}

variable "release_date" {
  description = "Publication date (YYYY-MM-DD, UTC) of that release for the adoption panel."
  type        = string

  validation {
    condition     = can(regex("^\\d{4}-\\d{2}-\\d{2}$", var.release_date))
    error_message = "release_date must be YYYY-MM-DD."
  }
}

variable "erasure_enabled" {
  description = "Per-role activation. Production remains false until the hosted lifecycle gate passes."
  type        = object({ production = bool, test = bool })
  default     = { production = false, test = false }
}

variable "erasure_secrets" {
  description = "Private per-role erasure credentials, supplied through a protected variable file."
  sensitive   = true
  type        = map(object({ active_key = string, master_keys = map(string), api_key = string }))
  default     = {}
}
