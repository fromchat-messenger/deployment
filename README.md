# FromChat Deployment

One-click installer for a production FromChat stack on Debian-based Linux.

## Quick install

```bash
curl -fsSL https://raw.githubusercontent.com/fromchat-messenger/app/main/deployment/install.sh | sudo bash
```

The installer:

1. Ensures root (re-runs via `sudo` if needed)
2. Verifies Debian/Ubuntu
3. Offers to install Docker (official script)
4. Creates `~/fromchat-server`
5. Lets you pick components: **backend**, **frontend**, **caddy**, **updater**
6. Optionally uses custom backend/web git URLs
7. Resolves the latest common semver tag (`v1.0`, `v1.0.0`, …)
8. Downloads only `compose.yml` from each repo at that tag
9. Merges compose files and replaces `build:` with `image: fromchat/<service>:<tag>`
   (caddy comes from backend compose; if not selected it is commented out)
10. Copies backend `src/livekit/compose.yaml` into the install dir
11. Runs `docker compose up -d`
12. Writes `updater/.env` when updater is selected (updater runs in the main stack)

## Classic deploy (offline, build on your PC)

For developers who build images **locally** and transfer them to a server via SSH + `docker pussh` — no registry pulls on the server.

```bash
cd deployment
python3 -m venv .venv && .venv/bin/pip install -r requirements-deploy.txt
# or reuse ../backend/.venv which already has rich + dotenv

./deploy.sh user@host ~/fromchat-server linux/arm64 --tag latest
```

Without `--components`, an interactive checklist opens (backend, frontend, caddy, updater). Non-interactive:

```bash
./deploy.sh user@host ~/fromchat-server linux/arm64 \
  --components backend,frontend,caddy,updater \
  --tag latest
```

This:

1. Builds all `fromchat/*` images from local `../backend`, `../Web`, `../updater`, and `backend/src/caddy`
2. Merges compose the same way as the installer (`generate-compose.py`)
3. Pulls any third-party images from the registry **on this machine**, then transfers everything via pussh
4. Syncs config to the server and restarts the `fromchat` systemd unit
5. Optionally installs the updater (also built locally)

The server never pulls from a registry during deploy. Your PC needs network access to pull base images (LiveKit, Docker `FROM` layers, unregistry for pussh).

Override source trees with `FROMCHAT_BACKEND_DIR`, `FROMCHAT_WEB_DIR`, `FROMCHAT_UPDATER_DIR`.  
`DEPLOYMENT_SERVER` and all server secrets live in **`deployment/.env.prod`** (copied to the server as `.env`).

The old entry point `backend/scripts/deploy.sh` redirects here.

## Generate compose only

```bash
./install.sh --generate-config backend,frontend,caddy \
  --tag v1.0.0 \
  --output-dir ~/fromchat-server \
  --backend-repo https://github.com/fromchat-messenger/backend.git \
  --web-repo https://github.com/fromchat-messenger/web.git
```

## Layout after install

```
~/fromchat-server/
  compose.yml                 # merged production stack (from backend ± web)
  .fromchat-version           # current release tag
  .env                        # copied from deployment/.env.prod
  src/livekit/compose.yaml    # from backend (LiveKit config)
  fromchat.service            # systemd unit (classic deploy)
  updater/.env                # when updater selected
```

## GitHub token (updater)

When **updater** is selected, create a token with `read:packages` and `repo` (works for GitHub and git.fromchat.ru):

https://github.com/settings/tokens/new?description=FromChat%20Updater&scopes=read:packages,repo

Official `github.com/fromchat-messenger/*` repositories automatically fall back to **git.fromchat.ru/FromChat/** if GitHub is unreachable.

## Environment overrides

| Variable | Default |
|----------|---------|
| `FROMCHAT_BACKEND_REPO` | `https://github.com/fromchat-messenger/backend.git` |
| `FROMCHAT_WEB_REPO` | `https://github.com/fromchat-messenger/web.git` |
| `FROMCHAT_APP_REPO` | `https://github.com/fromchat-messenger/app.git` (deployment + updater tooling) |
