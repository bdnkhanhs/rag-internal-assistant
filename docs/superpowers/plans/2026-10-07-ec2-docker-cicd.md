# EC2 Docker CI/CD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Docker packaging and a GitHub Actions workflow that builds, pushes to Docker Hub (`vthanhduc/rag-internal-assistant`), and deploys over SSH to EC2 `ec2-user@18.181.147.51`.

**Architecture:** Multi-stage Dockerfile builds the Spring Boot fat JAR; `docker-compose.yml` runs the image on EC2 with a persistent `data/` volume and `GEMINI_API_KEY` from `.env`. GitHub Actions builds/pushes on `master` push or `workflow_dispatch`, then SSHs to pull and restart the stack.

**Tech Stack:** Docker, Docker Compose v2, GitHub Actions, Docker Hub, OpenSSH, Eclipse Temurin JDK/JRE 25, Maven 3.9+, Spring Boot 4

## Global Constraints

- Java version: `25` (from `pom.xml`)
- App port: `8080`
- Image name: `vthanhduc/rag-internal-assistant`
- EC2 user/host: `ec2-user@18.181.147.51`
- Deploy path on EC2: `/home/ec2-user/rag-internal-assistant`
- Secrets live only in GitHub Secrets / EC2 `.env` — never commit PEM, tokens, or `.env`
- Triggers: `push` to `master` and `workflow_dispatch`
- Skip tests in Docker build (`-DskipTests`) for faster CI; local/CI unit tests remain optional out of this plan
- Do not rotate or store the Docker Hub PAT that was pasted in chat; instruct the operator to create a fresh token in GitHub Secrets

---

## File map

| File | Responsibility |
|------|----------------|
| `.dockerignore` | Keep build context small and secret-free |
| `.gitignore` | Ignore `.env`, `*.pem`, local data dumps if needed |
| `Dockerfile` | Multi-stage Maven build → JRE runtime image |
| `docker-compose.yml` | EC2 runtime: image, ports, env, volume, restart |
| `.github/workflows/deploy.yml` | Build, push Docker Hub, SCP compose, SSH deploy, smoke check |
| `README.md` | Secrets checklist + deploy notes |
| `docs/superpowers/specs/2026-10-07-ec2-docker-cicd-design.md` | Already exists (design) |

---

### Task 1: Ignore rules for Docker and secrets

**Files:**
- Create: `.dockerignore`
- Modify: `.gitignore` (create if missing)

**Interfaces:**
- Consumes: none
- Produces: ignore patterns so Docker context and git never include secrets or build junk

- [ ] **Step 1: Create `.dockerignore`**

```dockerignore
.git
.github
.vscode
docs
target
data
*.pem
.env
.env.*
sample.txt
**/.DS_Store
**/Thumbs.db
README.md
```

- [ ] **Step 2: Ensure `.gitignore` includes secrets and local runtime data**

If `.gitignore` does not exist, create it. Otherwise append any missing lines:

```gitignore
# Secrets / local env
.env
.env.*
*.pem

# Local runtime / build
target/
data/
.idea/
*.iml
.DS_Store
```

- [ ] **Step 3: Verify ignore files exist and list cleanly**

Run:

```bash
test -f .dockerignore && test -f .gitignore && echo OK
git check-ignore -v .env ssh_key_ec2.pem data/foo 2>/dev/null || true
```

Expected: `OK`; `.env` / `*.pem` / `data/` covered by `.gitignore`.

- [ ] **Step 4: Commit**

```bash
git add .dockerignore .gitignore
git commit -m "chore: ignore Docker secrets and local runtime data"
```

---

### Task 2: Multi-stage Dockerfile

**Files:**
- Create: `Dockerfile`

**Interfaces:**
- Consumes: `pom.xml`, `src/`
- Produces: image that runs the fat JAR on port 8080 with workdir `/app` and data under `/app/data`

- [ ] **Step 1: Create `Dockerfile`**

```dockerfile
# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN mkdir -p /app/data \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && chown -R app:app /app
COPY --from=build /build/target/rag-internal-assistant-1.0.0.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

Note: JAR name must match `artifactId`-`version`.jar from `pom.xml` (`rag-internal-assistant` / `1.0.0`). If the built JAR name differs, fix the `COPY` path after the first local build.

- [ ] **Step 2: Build the image locally (smoke)**

Run:

```bash
docker build -t vthanhduc/rag-internal-assistant:local .
```

Expected: build succeeds; final stage completes without COPY errors.

If COPY fails on JAR name, run `ls target/*.jar` after a Maven package (or inspect the failed build stage) and update the Dockerfile `COPY` line to the exact filename.

- [ ] **Step 3: Commit**

```bash
git add Dockerfile
git commit -m "feat: add multi-stage Dockerfile for Java 25 Spring Boot app"
```

---

### Task 3: docker-compose for EC2 runtime

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: image `vthanhduc/rag-internal-assistant:latest` (or overridden tag)
- Produces: service `app` on host port 8080, volume `./data`, env from `.env`

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  app:
    image: ${IMAGE_NAME:-vthanhduc/rag-internal-assistant}:${IMAGE_TAG:-latest}
    container_name: rag-internal-assistant
    ports:
      - "8080:8080"
    env_file:
      - .env
    environment:
      GEMINI_API_KEY: ${GEMINI_API_KEY}
    volumes:
      - ./data:/app/data
    restart: unless-stopped
```

- [ ] **Step 2: Validate compose file syntax locally**

Run:

```bash
docker compose -f docker-compose.yml config
```

Expected: rendered YAML printed; no schema errors. (`.env` may be missing locally — that is OK for `config` if Compose only warns; if it errors on missing `env_file`, create a temporary `.env` with `GEMINI_API_KEY=dummy` that is gitignored, run `config`, then delete the dummy if desired.)

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add docker-compose for EC2 app runtime"
```

---

### Task 4: GitHub Actions deploy workflow

**Files:**
- Create: `.github/workflows/deploy.yml`

**Interfaces:**
- Consumes: `Dockerfile`, `docker-compose.yml`, GitHub secrets listed below
- Produces: pushed Hub images + remote compose up on EC2

**Required GitHub repository secrets (operator sets in GitHub UI):**

| Secret | Example |
|--------|---------|
| `DOCKERHUB_USERNAME` | `vthanhduc` |
| `DOCKERHUB_TOKEN` | fresh Docker Hub PAT (not the leaked one) |
| `EC2_HOST` | `18.181.147.51` |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | full PEM contents |
| `GEMINI_API_KEY` | Gemini API key |

- [ ] **Step 1: Create `.github/workflows/deploy.yml`**

```yaml
name: Deploy to EC2

on:
  push:
    branches: [master]
  workflow_dispatch:

env:
  IMAGE_NAME: vthanhduc/rag-internal-assistant

jobs:
  deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKERHUB_USERNAME }}
          password: ${{ secrets.DOCKERHUB_TOKEN }}

      - name: Build and push
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          tags: |
            ${{ env.IMAGE_NAME }}:${{ github.sha }}
            ${{ env.IMAGE_NAME }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

      - name: Prepare SSH key
        run: |
          install -m 700 -d ~/.ssh
          printf '%s\n' "${{ secrets.EC2_SSH_KEY }}" > ~/.ssh/ec2.pem
          chmod 600 ~/.ssh/ec2.pem
          ssh-keyscan -H "${{ secrets.EC2_HOST }}" >> ~/.ssh/known_hosts

      - name: Copy compose file to EC2
        run: |
          ssh -i ~/.ssh/ec2.pem -o IdentitiesOnly=yes \
            "${{ secrets.EC2_USER }}@${{ secrets.EC2_HOST }}" \
            'mkdir -p ~/rag-internal-assistant/data'
          scp -i ~/.ssh/ec2.pem -o IdentitiesOnly=yes \
            docker-compose.yml \
            "${{ secrets.EC2_USER }}@${{ secrets.EC2_HOST }}:~/rag-internal-assistant/docker-compose.yml"

      - name: Write .env and deploy on EC2
        env:
          GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          ssh -i ~/.ssh/ec2.pem -o IdentitiesOnly=yes \
            "${{ secrets.EC2_USER }}@${{ secrets.EC2_HOST }}" \
            "set -euo pipefail
             cd ~/rag-internal-assistant
             umask 077
             printf 'GEMINI_API_KEY=%s\n' '${GEMINI_API_KEY}' > .env
             chmod 600 .env
             echo '${{ secrets.DOCKERHUB_TOKEN }}' | docker login -u '${{ secrets.DOCKERHUB_USERNAME }}' --password-stdin
             export IMAGE_NAME='${{ env.IMAGE_NAME }}'
             export IMAGE_TAG='${IMAGE_TAG}'
             export GEMINI_API_KEY='${GEMINI_API_KEY}'
             docker compose pull
             docker compose up -d
             docker logout
             for i in \$(seq 1 20); do
               if curl -fsS -o /dev/null http://127.0.0.1:8080/; then
                 echo 'Smoke check passed'
                 exit 0
               fi
               sleep 3
             done
             echo 'Smoke check failed'
             docker compose logs --tail=100
             exit 1
            "
```

- [ ] **Step 2: Validate workflow YAML locally**

Run:

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/deploy.yml')); print('YAML OK')"
```

If PyYAML is missing:

```bash
# macOS alternative
ruby -ryaml -e "YAML.load_file('.github/workflows/deploy.yml'); puts 'YAML OK'"
```

Expected: `YAML OK`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/deploy.yml
git commit -m "ci: deploy Docker image to EC2 via GitHub Actions"
```

---

### Task 5: README deploy documentation

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: secret names and EC2 URL from this plan
- Produces: operator instructions to finish setup

- [ ] **Step 1: Append a Deploy section to `README.md`**

Keep existing README content. Append:

```markdown
## Deploy (AWS EC2 + GitHub Actions)

### One-time EC2 setup
1. Security group inbound rules: TCP 22 (SSH), TCP 8080 (app).
2. Confirm Docker and Compose: `docker --version` and `docker compose version`.
3. Ensure `ec2-user` can run Docker without root issues (member of `docker` group).

### GitHub repository secrets
Set these under **Settings → Secrets and variables → Actions**:

| Secret | Description |
|--------|-------------|
| `DOCKERHUB_USERNAME` | Docker Hub username (`vthanhduc`) |
| `DOCKERHUB_TOKEN` | Docker Hub access token (create a new one; do not reuse leaked tokens) |
| `EC2_HOST` | `18.181.147.51` |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | Full contents of the EC2 `.pem` private key |
| `GEMINI_API_KEY` | Gemini API key for the running app |

### Deploy
- Push to `master`, or run **Actions → Deploy to EC2 → Run workflow**.
- App URL: http://18.181.147.51:8080

### On the server
```bash
cd ~/rag-internal-assistant
docker compose ps
docker compose logs -f --tail=100
```
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add EC2 Docker Hub CI/CD deploy instructions"
```

---

### Task 6: Operator checklist (no code) — secrets, security group, first deploy

**Files:**
- None (manual verification)

**Interfaces:**
- Consumes: completed Tasks 1–5 pushed to `origin/master`
- Produces: live app on EC2

- [ ] **Step 1: Rotate Docker Hub token if any PAT was exposed in chat**

Create a new PAT at Docker Hub → Account Settings → Personal access tokens. Revoke the old one. Store only the new value in `DOCKERHUB_TOKEN`.

- [ ] **Step 2: Add all six GitHub Actions secrets** listed in Task 4.

- [ ] **Step 3: Open EC2 security group ports 22 and 8080** (if not already open).

- [ ] **Step 4: Confirm Docker Compose on EC2**

```bash
ssh -i /Users/vothanhduc/Downloads/ssh_key_ec2.pem ec2-user@18.181.147.51 'docker compose version'
```

Expected: Compose version printed. If missing, install Docker Compose plugin for Amazon Linux 2023 before deploying.

- [ ] **Step 5: Push commits / trigger workflow**

```bash
git push -u origin HEAD
```

Then open GitHub Actions and confirm **Deploy to EC2** succeeds.

- [ ] **Step 6: Smoke-test from your machine**

```bash
curl -sS -o /dev/null -w "%{http_code}\n" http://18.181.147.51:8080/
```

Expected: `200` (or `302` to login). Browser: open http://18.181.147.51:8080 and sign in with demo accounts from README.

---

## Spec coverage (self-review)

| Spec requirement | Task |
|------------------|------|
| Multi-stage Dockerfile Java 25 | Task 2 |
| docker-compose port/volume/env | Task 3 |
| GH Actions build/push Hub + SSH deploy | Task 4 |
| Triggers: master + workflow_dispatch | Task 4 |
| GitHub secrets list | Tasks 4–5 |
| EC2 SG 22/8080 + compose | Tasks 5–6 |
| Smoke check | Task 4 + Task 6 |
| Persist `data/` | Task 3 |
| No secrets in git | Tasks 1, 4, 6 |
| README operator docs | Task 5 |

## Placeholder / consistency check

- Image name consistent: `vthanhduc/rag-internal-assistant`
- JAR: `rag-internal-assistant-1.0.0.jar` matches `pom.xml`
- Deploy dir: `~/rag-internal-assistant`
- No TBD/TODO left in steps
