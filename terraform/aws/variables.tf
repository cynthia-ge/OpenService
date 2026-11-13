variable "name" {
  type    = string
  default = "openservice"

}
variable "region" {
  type    = string
  default = "eu-west-1"

}
variable "profile" {
  type        = string
  default     = null
  description = "An explicitly selected AWS CLI profile; null uses the environment credential chain."

}
variable "image" {

  type        = string
  description = "Published linux/amd64 OpenService image in public ECR, pinned to an immutable release tag."
  validation {
    condition     = startswith(var.image, "public.ecr.aws/") && !endswith(var.image, ":latest")
    error_message = "Provide a public ECR image with an immutable release tag, not latest."

  }

}
