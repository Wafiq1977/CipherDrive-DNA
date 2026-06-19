# 🚀 CipherDrive-DNA MVP — Deployment Guide

This guide walks you through deploying CipherDrive-DNA MVP to a public URL so anyone can access it.

> **Recommended platform: [Render.com](https://render.com)** — easiest path for Spring Boot + MySQL + object storage, with a generous free credit ($300 for new users).

---

## 📋 Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Quick Deploy to Render (recommended)](#2-quick-deploy-to-render-recommended)
3. [Local Docker Deployment](#3-local-docker-deployment)
4. [Alternative Platforms](#4-alternative-platforms)
5. [Post-deploy: Verify it works](#5-post-deploy-verify-it-works)
6. [Production Hardening Checklist](#6-production-hardening-checklist)
7. [Troubleshooting](#7-troubleshooting)

---

## 1. Prerequisites

Before you start:

- ✅ GitHub account (you already have one)
- ✅ Repository pushed to GitHub (already done — https://github.com/Wafiq1977/CipherDrive-DNA)
- ✅ Render.com account → [sign up free](https://dashboard.render.com/register) (gets $300 credit)
- ✅ `openssl` installed locally (to generate JWT secret)
  - macOS/Linux: already installed
  - Windows: use Git Bash or WSL

### Generate a JWT secret now

You'll need this in step 2. Run this in your terminal and **save the output**:

```bash
openssl rand -base64 32 | base64
```

Example output (yours will be different):

```
dTdRQ3NlY3JldEtleUZvckpXVFRva2VuR2VuZXJhdGlvbg==
```

> ⚠️ Save this somewhere safe — you'll paste it into Render as the `JWT_SECRET_KEY` env var. **Never commit it to git.**

---

## 2. Quick Deploy to Render (recommended)

### Step 1: Fork/clone the repo (skip if you own it)

Your repo is at `https://github.com/Wafiq1977/CipherDrive-DNA` — Render will use it directly.

### Step 2: Create a Blueprint deployment on Render

1. Go to 👉 https://dashboard.render.com/select-repo?type=blueprint
2. Connect your GitHub account if you haven't already.
3. Select the `Wafiq1977/CipherDrive-DNA` repository.
4. Render detects `render.yaml` at the repo root and shows you the services it will create:
   - `cipherdrive-dna` (Web Service — the Spring Boot app)
   - `cipherdrive-mysql` (Private MySQL service)

### Step 3: Fill in the secret environment variables

In the Render UI before clicking "Apply", you'll be prompted for **secret** env vars (marked `sync: false` in `render.yaml`):

| Variable | Value |
|----------|-------|
| `JWT_SECRET_KEY` | The Base64-encoded secret you generated in [Prerequisites](#1-prerequisites) |
| `MINIO_ACCESS_KEY` | Any random string, e.g. run `openssl rand -hex 16` |
| `MINIO_SECRET_KEY` | Run `openssl rand -hex 32` (longer = more secure) |

### Step 4: Click "Apply"

Render will now:

1. Provision a MySQL instance (~2 min)
2. Build the Docker image from your `Dockerfile` (~5 min first time, faster on rebuilds)
3. Start the Spring Boot app on a `*.onrender.com` URL

You'll see live build logs. The first build takes about 5 minutes.

### Step 5: Initialize the database schema

The app uses `ddl-auto: validate` (safe for production), which means **the schema must already exist** before the app starts. Render MySQL gives you an empty database.

To load the schema:

1. In Render dashboard → click the `cipherdrive-mysql` service.
2. Click the **"Shell"** tab.
3. Run the following:

```bash
# Render's MySQL shell has psql-like access; use the mysql CLI:
mysql -u $MYSQL_USER -p"$MYSQL_PASSWORD" $MYSQL_DATABASE < cipherdrive_dna_mvp.sql
```

If the `mysql` CLI isn't available in the shell, you can use an external GUI like **DBeaver** or **MySQL Workbench** with Render's external connection string (shown on the MySQL service page).

### Step 6: Wait for the app to start

Back on the `cipherdrive-dna` Web Service page:

- Wait for the build & deploy to complete
- The status will change from **"Building"** → **"Live"**
- You'll see a URL like `https://cipherdrive-dna-xxxx.onrender.com`

### Step 7: Open the app 🎉

Click the URL. You should see the Spring Boot default response (or a 401 from the security filter — both are good signs the app is running).

Test the health endpoint:

```bash
curl https://cipherdrive-dna-xxxx.onrender.com/actuator/health
# → {"status":"UP"}
```

You're live! Share the URL with anyone. 🌍

---

## 3. Local Docker Deployment

Want to run the whole stack locally first? One command:

```bash
cp .env.example .env
# Edit .env — set real passwords
docker compose up -d
```

This starts:
- MySQL on `localhost:3306`
- MinIO on `localhost:9000` (S3 API) + `localhost:9001` (web console)
- CipherDrive-DNA app on `localhost:8080`

Check status:

```bash
docker compose ps
docker compose logs -f app
```

Stop everything:

```bash
docker compose down       # stop containers, keep data
docker compose down -v    # stop + delete all data
```

---

## 4. Alternative Platforms

### Railway.app

1. Go to https://railway.app/new
2. Connect your GitHub repo
3. Railway auto-detects Dockerfile
4. Add a MySQL plugin from Railway's marketplace
5. Set env vars (same as Render)
6. Deploy

**Cost**: ~$5/month after free trial (no permanent free tier)

### Fly.io

1. Install `flyctl`: https://fly.io/docs/hands-on/install-flyctl/
2. Run from your repo:
   ```bash
   fly launch
   fly deploy
   ```
3. Fly generates `fly.toml` automatically.
4. Create a MySQL/Postgres cluster: `fly mysql create` or use Upstash.

**Cost**: Free for 3 tiny VMs permanently, but you pay for persistent volumes.

### Self-hosted VPS (DigitalOcean / Hetzner / Linode)

1. Rent a $4-6/month VPS (Ubuntu 22.04+)
2. SSH in, install Docker:
   ```bash
   curl -fsSL https://get.docker.com | sh
   ```
3. Clone your repo:
   ```bash
   git clone https://github.com/Wafiq1977/CipherDrive-DNA.git
   cd CipherDrive-DNA
   ```
4. Create `.env` with real secrets, then:
   ```bash
   docker compose up -d
   ```
5. Point your domain's A record to the VPS IP.
6. Install Caddy for automatic HTTPS:
   ```bash
   docker run -d --name caddy --restart=unless-stopped \
     -p 80:80 -p 443:443 \
     -v $PWD/Caddyfile:/etc/caddy/Caddyfile \
     caddy
   ```

Sample `Caddyfile`:

```
your-domain.com {
    reverse_proxy localhost:8080
}
```

---

## 5. Post-deploy: Verify it works

### Smoke test endpoints

Replace `YOUR_URL` with your deployment URL:

```bash
# 1. Health check
curl https://YOUR_URL/actuator/health
# Expected: {"status":"UP"}

# 2. Register a user
curl -X POST https://YOUR_URL/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "email": "test@example.com",
    "password": "Password123!"
  }'

# 3. Login
curl -X POST https://YOUR_URL/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "Password123!"
  }'
# Save the access token from the response

# 4. Access a protected endpoint
TOKEN="..."
curl https://YOUR_URL/api/dna/profile \
  -H "Authorization: Bearer $TOKEN"
```

If all three work — you're in business. 🚀

---

## 6. Production Hardening Checklist

Before sharing your URL widely:

- [ ] **JWT secret** is at least 32 bytes random, NOT the placeholder from `.env.example`
- [ ] **MySQL password** is at least 16 characters random
- [ ] **MinIO credentials** are not the defaults (`minioadmin`/`minioadmin`)
- [ ] **CORS** configured properly in `SecurityConfig.java` — restrict to your frontend domain only
- [ ] **Rate limiting** enabled (consider Bucket4j or Cloudflare in front)
- [ ] **HTTPS** enforced — Render/Railway/Fly do this automatically
- [ ] **Database backups** scheduled (Render does this automatically for paid MySQL)
- [ ] **Log monitoring** — Render has built-in log streams; consider integrating with Papertrail
- [ ] **Disk size** sufficient for expected file uploads (upgrade from 1GB free tier if needed)
- [ ] **Memory** sufficient — Spring Boot + MySQL in 512MB can be tight; upgrade to 1GB if you see OOMKilled
- [ ] **DNS** configured if using a custom domain (add a CNAME to your Render URL)

---

## 7. Troubleshooting

### App fails to start with "Failed to determine a suitable driver class"

→ `MYSQL_URL` env var is not set. Check Render dashboard → Environment tab.

### App fails to start with "Access denied for user"

→ MySQL credentials mismatch. Verify `MYSQL_USER` / `MYSQL_PASSWORD` match what Render's MySQL service shows.

### `ddl-auto: validate` errors (table not found)

→ You haven't loaded the SQL schema. See [Step 5](#step-5-initialize-the-database-schema).

### MinIO healthcheck fails

→ The MinIO sidecar pattern isn't fully wired up in `render.yaml` yet. For initial deployment, the app will start but file upload endpoints won't work.

**Workaround**: Use external S3 instead — set `MINIO_ENDPOINT` to `https://s3.amazonaws.com` and use real AWS S3 credentials.

### Build times out on Render

→ First build can take 5-8 minutes. If it times out, increase the build timeout in Render dashboard → Settings → Build Timeout (max 60 min).

### App is slow / OOMKilled

→ 512MB RAM is the minimum. Spring Boot + JVM + Hibernate need headroom.
- Upgrade to the 1GB plan (~$15/month)
- Or tune `JAVA_OPTS` to use less memory:
  ```
  -XX:+UseContainerSupport -XX:MaxRAMPercentage=60.0 -Xss256k
  ```

### `401 Unauthorized` on every endpoint

→ Expected behavior — all endpoints except `/api/auth/**` and `/actuator/health` require a JWT.

Get a token first via `/api/auth/login`, then send it as:
```
Authorization: Bearer <your-jwt-token>
```

---

## 📞 Need help?

- Render docs: https://render.com/docs
- Spring Boot on Render: https://render.com/docs/deploy-spring-boot
- Open an issue: https://github.com/Wafiq1977/CipherDrive-DNA/issues
