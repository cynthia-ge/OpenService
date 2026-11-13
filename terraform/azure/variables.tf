variable "subscription_id" {
  type = string
}
variable "location" {
  type    = string
  default = "westeurope"

}
variable "name" {
  type    = string
  default = "openservice"

}
variable "image" {
  type        = string
  description = "Publicly pullable linux/amd64 OpenService image with an immutable release tag or digest."
  validation {
    condition     = !endswith(var.image, ":latest") && length(var.image) > 10
    error_message = "Use a release image, not latest."

  }

}
