variable "project_id" {
  type = string
}
variable "region" {
  type    = string
  default = "europe-west1"

}
variable "name" {
  type    = string
  default = "openservice"

}
variable "image" {
  type        = string
  description = "OpenService linux/amd64 image accessible to Cloud Run, pinned to a release tag or digest."
  validation {
    condition     = !endswith(var.image, ":latest") && length(var.image) > 10
    error_message = "Use a release image, not latest."

  }

}
