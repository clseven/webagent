# AIO LibreOffice Sandbox Image Design

## Goal

Provide LibreOffice inside every newly created sandbox while preserving the
existing AIO sandbox entrypoint, browser support, and asynchronous shell tools.

## Design

Create a small derived Docker image from
`ghcr.io/agent-infra/sandbox:latest`. Install LibreOffice and Noto CJK fonts as
root and remove the apt package lists. Keep the base image's default root user;
the current AIO image does not define a `gem` system user. Do not replace the
base image entrypoint or command.

Tag the local image as `agent-infra/sandbox-office:latest`. The repository name
intentionally contains `agent-infra/sandbox`, because the current Java code uses
that substring to classify the configured image as an AIO sandbox.

Change the default value of `SANDBOX_IMAGE` in `application.yml` to the derived
image. Keep the environment-variable expression so deployments can still
override the default without changing source files.

## Verification

1. Build the image from `docker/sandbox-office/Dockerfile`.
2. Run `soffice --version` inside the image.
3. Confirm `/opt/gem/run.sh` remains executable.
4. Run the Maven test suite to ensure the configuration change does not break
   the application.

## Operational Notes

The image must be available to the Docker daemon used by the OpenSandbox service
at `SANDBOX_DOMAIN`. Existing sandboxes keep their original image; users must
create new sandboxes after the Spring Boot service restarts.
