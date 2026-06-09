# AIO LibreOffice Sandbox Image Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and select an AIO-compatible sandbox image that includes LibreOffice and Chinese fonts.

**Architecture:** Extend the existing AIO image without changing its entrypoint. Keep `SANDBOX_IMAGE` as an override while changing its default to a local image name that the current Java AIO detector recognizes.

**Tech Stack:** Docker, Debian/Ubuntu apt packages, Spring Boot YAML configuration, Maven

---

### Task 1: Add the derived sandbox image

**Files:**
- Create: `docker/sandbox-office/Dockerfile`

- [x] **Step 1: Add a Dockerfile structure check**

Run after creating the file:

```powershell
$text = Get-Content -Raw docker/sandbox-office/Dockerfile
@(
  $text.Contains('FROM ghcr.io/agent-infra/sandbox:latest'),
  $text.Contains('libreoffice'),
  $text.Contains('fonts-noto-cjk'),
  -not $text.Contains('USER gem')
) -notcontains $false
```

Expected: `True`.

- [x] **Step 2: Implement the derived image**

```dockerfile
FROM ghcr.io/agent-infra/sandbox:latest

USER root

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        libreoffice \
        fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*
```

- [x] **Step 3: Build and inspect the image**

```powershell
docker build -t agent-infra/sandbox-office:latest docker/sandbox-office
docker run --rm --entrypoint soffice agent-infra/sandbox-office:latest --version
docker run --rm --entrypoint sh agent-infra/sandbox-office:latest -c "test -x /opt/gem/run.sh"
```

Expected: the build succeeds, LibreOffice prints its version, and the entrypoint
script check exits with code 0. The derived image retains the base image's
default root user because the AIO image does not define a `gem` user.

### Task 2: Select the new default image

**Files:**
- Modify: `src/main/resources/application.yml`

- [x] **Step 1: Change only the sandbox image default**

```yaml
image: ${SANDBOX_IMAGE:agent-infra/sandbox-office:latest}
```

- [x] **Step 2: Verify AIO classification remains compatible**

```powershell
$image = 'agent-infra/sandbox-office:latest'
$image.Contains('agent-infra/sandbox')
```

Expected: `True`.

### Task 3: Document build and operation

**Files:**
- Modify: `README.md`

- [x] **Step 1: Update the configuration example**

Use `agent-infra/sandbox-office:latest` as the sandbox image.

- [x] **Step 2: Add build and verification commands**

Document the Docker build, `soffice --version`, service restart, and the need to
create a fresh sandbox.

- [x] **Step 3: Run project verification**

```powershell
mvn test
```

Expected: Maven exits with code 0 and reports no test failures.
