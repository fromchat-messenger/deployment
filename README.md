# FromChat Deployment

One-click installer for a production FromChat stack on Debian-based Linux.

## Quick install

```bash
curl -fsSL https://l.fromchat.ru/install|sudo bash
```

The installer:

1. Installs Docker + Compose plugin if missing
2. Downloads the published `compose.yml` (`restart: always` on all services)
3. Runs the backend `.env` generator into `~/fromchat-server/.env`
4. Prints next steps (configure Caddyfile, then `docker compose up -d`)

No systemd unit — containers use Docker `restart: always`.

Short links on `l.fromchat.ru`:

| Path | Target |
|------|--------|
| `/install` | This installer script (GitHub → Gitea fallback) |
| `/telegram` | https://t.me/fromchat_ch |
| `/max` | https://mxg.su/fromchat_ch |

## After install

```bash
cd ~/fromchat-server
docker compose --env-file .env up -d
```

## Layout after install

```
~/fromchat-server/
  compose.yml                 # published production stack
  .env                        # from generate:env
  compliance_keypair.txt      # keep the private key offline
  data/prod/                  # runtime data (Caddy ACME under data/prod/caddy/)
  firebase-cert.json          # optional; empty placeholder created by installer
```

## Classic deploy (offline, build on your PC)

For developers who build images **locally** and transfer them to a server via SSH + `docker pussh`:

```bash
cd deployment
./deploy.sh user@host ~/fromchat-server linux/arm64 --tag latest
```

Syncs `compose.yml` + `.env` to the server, pushes images via `docker pussh`, then runs `docker compose up -d` remotely (containers use `restart: always` — no systemd unit).

Publish multi-arch images with `./publish.sh`. CI regenerates root `compose.yml` from the latest published release.
