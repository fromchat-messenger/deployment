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
10. Opens `Caddyfile` in nano when caddy is selected
11. Runs `docker compose up -d --wait`
12. Optionally starts the [updater](../updater) service

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
`DEPLOYMENT_SERVER` in the backend (or deployment) `.env` still works.

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
  compose.yml          # merged production stack
  .fromchat-version    # current release tag
  .env                 # secrets (create via backend generate:env)
  Caddyfile            # when caddy selected
  config/livekit.yaml
  updater/             # when updater selected
    compose.yml
    .env
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
