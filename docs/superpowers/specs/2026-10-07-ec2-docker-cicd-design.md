# EC2 Docker CI/CD Design

Date: 2026-10-07  
Status: Approved (pending final user review of this doc)

## Goal

Deploy `rag-internal-assistant` (Spring Boot + Docker) to an existing AWS EC2 instance via GitHub Actions, using Docker Hub as the image registry and SSH for remote deploy.

## Context

- App: Spring Boot 4 / Java 25, listens on port `8080`, requires `GEMINI_API_KEY`
- Persistence: H2 file DB + vector store under `./data` (must survive container restarts)
- EC2: `ec2-user@18.181.147.51` (Amazon Linux 2023), Docker 25.0.14 already installed
- SSH key: local PEM (`ssh_key_ec2.pem`); connectivity verified
- GitHub repo: `bdnkhanhs/rag-internal-assistant` (branch `master`)
- Docker Hub: namespace `vthanhduc`

## Chosen approach

**Build on GitHub Actions → push to Docker Hub → SSH to EC2 → `docker compose pull && up -d`**

Rejected alternatives:

- Build on EC2 over SSH: simpler, but 2 GB RAM is tight for a Java 25 Maven build
- Amazon ECR: better for AWS-native scale-out; more IAM setup than needed now

## Architecture

```
Push to master / workflow_dispatch
        │
        ▼
GitHub Actions
  1. Build multi-stage Docker image
  2. Push vthanhduc/rag-internal-assistant:<sha> and :latest
  3. SSH to EC2
  4. docker compose pull && up -d
        │
        ▼
http://18.181.147.51:8080
```

## Components

### 1. Dockerfile (multi-stage)

- **Build stage:** Maven + JDK 25, `mvn -B -DskipTests package`
- **Runtime stage:** JRE 25, copy fat JAR, expose `8080`
- Working directory `/app`; data path `/app/data` (volume-mounted)
- `.dockerignore` excludes `target/`, `.git/`, docs, IDE files, `*.pem`, `.env*`

### 2. docker-compose.yml

- Service name: `app`
- Image: `vthanhduc/rag-internal-assistant:latest` (overridable via tag in deploy)
- Ports: `8080:8080`
- Environment: `GEMINI_API_KEY` (required)
- Volume: `./data:/app/data` for H2 + vectors
- `restart: unless-stopped`

Deploy layout on EC2:

```
/home/ec2-user/rag-internal-assistant/
  docker-compose.yml
  .env                 # GEMINI_API_KEY=... (mode 600, not in git)
  data/                # persisted volume
```

### 3. GitHub Actions workflow (`.github/workflows/deploy.yml`)

**Triggers**

- `push` to `master`
- `workflow_dispatch` (manual)

**Steps**

1. Checkout
2. Log in to Docker Hub
3. Build and push image tags: `${{ github.sha }}` and `latest`
4. Copy/sync `docker-compose.yml` to EC2 (via SCP) if missing or updated
5. Ensure `.env` on EC2 contains `GEMINI_API_KEY` from GitHub secret (overwrite on each deploy so key rotation works)
6. SSH: `docker compose pull && docker compose up -d`
7. Smoke check: `curl -f --retry 10 --retry-delay 3 http://127.0.0.1:8080/` (or login path) from EC2

### 4. Secrets (GitHub repository secrets only)

| Secret | Value / source |
|--------|----------------|
| `DOCKERHUB_USERNAME` | `vthanhduc` |
| `DOCKERHUB_TOKEN` | Docker Hub PAT (**rotate** any token that was pasted in chat) |
| `EC2_HOST` | `18.181.147.51` |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | Full PEM private key contents |
| `GEMINI_API_KEY` | Gemini API key |

Never commit PEM files, `.env`, or tokens.

### 5. EC2 prerequisites (manual / one-time)

- Security group inbound: TCP **22** (SSH), TCP **8080** (app)
- Docker Compose v2 available (`docker compose version`)
- Directory `/home/ec2-user/rag-internal-assistant` created by first deploy (or prep script)
- `ec2-user` can run Docker (docker group / sudo as needed; prefer docker group)

## Data & configuration

- App config stays in `application.properties`; only secrets and image tag change per environment
- H2 URL remains file-based under `/app/data` inside the container → host `./data`
- Demo users from app initializer remain as documented in README

## Failure behavior

- Failed build or Docker Hub push → no deploy
- Failed SSH / compose → workflow fails; previous healthy container remains until a successful `up -d` replaces it
- Failed smoke check → workflow fails (operator investigates logs via `docker compose logs`)

## Out of scope

- HTTPS / reverse proxy (nginx, ALB, ACM)
- Custom domain / Route53
- Blue-green or zero-downtime multi-instance
- Migrating H2 to RDS/Postgres
- Building on the EC2 host

## Success criteria

1. Push to `master` (or manual dispatch) builds and pushes an image to Docker Hub
2. EC2 runs the new container on port 8080
3. `http://18.181.147.51:8080` serves the login page
4. App can call Gemini using `GEMINI_API_KEY` from EC2 `.env`
5. Redeploy preserves uploaded docs / vectors under `data/`
6. No secrets appear in the git history
