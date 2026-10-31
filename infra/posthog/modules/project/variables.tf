variable "name" {
  description = "Project display name."
  type        = string
}

variable "organization_id" {
  description = "Organization that owns the project."
  type        = string
}

variable "repository" {
  description = "GitHub repository (owner/name) named in the dashboard description."
  type        = string
}

variable "release_version" {
  description = "Exact app_version string the release-adoption panel measures."
  type        = string
}

variable "release_date" {
  description = "Publication date (YYYY-MM-DD, UTC) of that release."
  type        = string
}
