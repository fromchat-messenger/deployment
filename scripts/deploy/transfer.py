"""Image pussh, rsync deployment layout, Firebase cert, remote docker compose."""

from __future__ import annotations

import os
import shlex
import subprocess
import sys
import urllib.request
from pathlib import Path

from deploy.paths import ProjectPaths
from deploy.ssh_auth import SshCredentials, scp_argv, ssh_argv, ssh_common_options
from deploy.util import (
    image_exists_locally,
    local_image_layer_fp,
    read_cached_layer_fp,
    remote_image_layer_fp,
    write_image_cache_fields,
)
import deploy.ui as ui

UNREGISTRY_IMAGE = "ghcr.io/psviderski/unregistry"
PUSSH_PLUGIN_URL = (
    "https://raw.githubusercontent.com/psviderski/unregistry/main/docker-pussh"
)

REMOTE_COMPOSE_UP_SCRIPT = r"""set -e

REMOTE_SUDO_PASS="${SUDO_PASSWORD:-}"
REMOTE_DEPLOY_PATH="${DEPLOY_PATH:-}"
export SUDO_PROMPT=""

sudo_cmd() {
    if [ -n "$REMOTE_SUDO_PASS" ]; then
        echo "$REMOTE_SUDO_PASS" | sudo -S -p '' "$@" 2>/dev/null
    else
        sudo "$@" 2>/dev/null
    fi
}

if [ -z "$REMOTE_DEPLOY_PATH" ]; then
    echo "❌ DEPLOY_PATH is not set"
    exit 1
fi

mkdir -p "$REMOTE_DEPLOY_PATH/config" \
    "$REMOTE_DEPLOY_PATH/data/prod/files" \
    "$REMOTE_DEPLOY_PATH/data/prod/logs/messaging" \
    "$REMOTE_DEPLOY_PATH/data/prod/logs/file_storage" \
    "$REMOTE_DEPLOY_PATH/data/prod/postgres" \
    "$REMOTE_DEPLOY_PATH/data/prod/caddy/data" \
    "$REMOTE_DEPLOY_PATH/data/prod/caddy/config"
cd "$REMOTE_DEPLOY_PATH"

# Match container UIDs (app=1000, messaging=1001, filestorage=1002).
# Do not touch postgres data dir ownership.
sudo_cmd chown -R 1000:1000 data/prod/logs 2>/dev/null || true
sudo_cmd chown -R 1001:1001 data/prod/logs/messaging 2>/dev/null || true
sudo_cmd chown -R 1002:1002 data/prod/files data/prod/logs/file_storage 2>/dev/null || true

if [ ! -f "$REMOTE_DEPLOY_PATH/.env" ]; then
    echo "❌ .env file not found at $REMOTE_DEPLOY_PATH/.env"
    exit 1
fi

if [ ! -f "$REMOTE_DEPLOY_PATH/compose.yml" ]; then
    echo "❌ compose.yml not found at $REMOTE_DEPLOY_PATH/compose.yml"
    exit 1
fi

# Remove legacy systemd unit (stack uses docker compose restart: always).
if systemctl is-active --quiet fromchat 2>/dev/null; then
    sudo_cmd systemctl stop fromchat
fi
sudo_cmd systemctl disable fromchat 2>/dev/null || true
if [ -f /etc/systemd/system/fromchat.service ]; then
    sudo_cmd rm -f /etc/systemd/system/fromchat.service
    sudo_cmd systemctl daemon-reload 2>/dev/null || true
fi
rm -f "$REMOTE_DEPLOY_PATH/fromchat.service" 2>/dev/null || true

docker compose --env-file .env down --remove-orphans > /dev/null 2>&1 || true
docker compose --env-file .env up -d --remove-orphans

sleep 3
if ! docker compose --env-file .env ps --status running 2>/dev/null | grep -q .; then
    echo "❌ No running containers after docker compose up -d"
    docker compose --env-file .env ps -a
    exit 1
fi
"""


class DeployTransfer:
    def __init__(self, paths: ProjectPaths) -> None:
        self._paths = paths

    @staticmethod
    def _pussh_works() -> bool:
        plugin = DeployTransfer._plugin_dir() / "docker-pussh"
        if not (plugin.is_file() or plugin.is_symlink()):
            return False
        r = subprocess.run(
            ["docker", "pussh"],
            capture_output=True,
            text=True,
        )
        out = f"{r.stdout or ''}{r.stderr or ''}"
        return "IMAGE and HOST are required" in out or "Upload a Docker image" in out

    @staticmethod
    def _plugin_dir() -> Path:
        return Path.home() / ".docker" / "cli-plugins"

    def _install_pussh_plugin(self) -> bool:
        plugin_dir = self._plugin_dir()
        plugin_dir.mkdir(parents=True, exist_ok=True)
        dest = plugin_dir / "docker-pussh"

        # Prefer Homebrew install when present.
        brew_prefix = subprocess.run(
            ["brew", "--prefix"],
            capture_output=True,
            text=True,
        )
        candidates: list[Path] = []
        if brew_prefix.returncode == 0 and brew_prefix.stdout.strip():
            candidates.append(Path(brew_prefix.stdout.strip()) / "bin" / "docker-pussh")
        for p in ("/opt/homebrew/bin/docker-pussh", "/usr/local/bin/docker-pussh"):
            candidates.append(Path(p))

        for cand in candidates:
            if cand.is_file():
                ui.substep(f"Linking docker-pussh from {cand}…")
                if dest.is_symlink() or dest.exists():
                    dest.unlink()
                dest.symlink_to(cand)
                os.chmod(cand, 0o755)
                return self._pussh_works()

        ui.substep("Downloading docker-pussh plugin…")
        try:
            with urllib.request.urlopen(PUSSH_PLUGIN_URL, timeout=60) as resp:
                dest.write_bytes(resp.read())
            dest.chmod(0o755)
        except OSError as exc:
            ui.error(f"Failed to download docker-pussh: {exc}")
            return False
        return self._pussh_works()

    def ensure_pussh(self) -> None:
        if self._pussh_works():
            return
        ui.warning("docker pussh plugin not found — installing…")
        if not self._install_pussh_plugin():
            ui.error("docker pussh plugin is required for image transfer")
            print("   Install manually:")
            print("     brew install psviderski/tap/docker-pussh")
            print("     mkdir -p ~/.docker/cli-plugins")
            print(
                "     ln -sf \"$(brew --prefix)/bin/docker-pussh\" "
                "~/.docker/cli-plugins/docker-pussh"
            )
            raise SystemExit(1)
        ui.success("docker pussh ready")

    def ensure_unregistry(self, creds: SshCredentials) -> None:
        """Server needs unregistry for pussh; pull locally then transfer if missing."""
        check = (
            "sudo docker images --format '{{.Repository}}:{{.Tag}}' | "
            f"grep -qE '^{UNREGISTRY_IMAGE}(:|$)'"
        )
        if subprocess.run(ssh_argv(creds.server, check), capture_output=True).returncode == 0:
            return
        import deploy.docker_local as docker_local

        if not image_exists_locally(UNREGISTRY_IMAGE):
            docker_local.pull_images([UNREGISTRY_IMAGE])
        ui.substep("Transferring unregistry image to server (one-time setup)…")
        if subprocess.run(["docker", "pussh", UNREGISTRY_IMAGE, creds.server]).returncode != 0:
            ui.error("Failed to transfer unregistry image to server")
            raise SystemExit(1)

    def _images_needing_transfer(
        self,
        creds: SshCredentials,
        images: list[str],
    ) -> tuple[list[str], list[str]]:
        need: list[str] = []
        skip: list[str] = []
        cache_root = self._paths.local_image_cache_dir
        for image in images:
            local_fp = local_image_layer_fp(image)
            if not local_fp:
                need.append(image)
                continue

            cached_local = read_cached_layer_fp(cache_root, image, "local")
            cached_remote = read_cached_layer_fp(cache_root, image, "remote")
            if cached_local == local_fp and cached_remote == local_fp:
                skip.append(image)
                continue

            remote_fp = remote_image_layer_fp(creds.server, image)
            if remote_fp and remote_fp == local_fp:
                skip.append(image)
                write_image_cache_fields(
                    cache_root,
                    image,
                    **{
                        "local.layer.sha256": local_fp,
                        "remote.layer.sha256": remote_fp,
                    },
                )
            else:
                need.append(image)
        return need, skip

    def pussh_images(self, creds: SshCredentials, images: list[str]) -> None:
        if not images:
            return
        need, skip = self._images_needing_transfer(creds, images)
        if skip:
            ui.substep(f"Transfer cache hit — skipping: {', '.join(skip)}")
        if not need:
            ui.success(f"All {len(skip)} image(s) already on server")
            return
        ui.step("Transferring images to server")
        self.ensure_unregistry(creds)
        for image in need:
            ui.substep(f"Transferring {image}...")
            if subprocess.run(["docker", "pussh", image, creds.server]).returncode != 0:
                ui.error(f"Failed to transfer {image}")
                raise SystemExit(1)
            layer_fp = local_image_layer_fp(image)
            if layer_fp:
                write_image_cache_fields(
                    self._paths.local_image_cache_dir,
                    image,
                    **{
                        "local.layer.sha256": layer_fp,
                        "remote.layer.sha256": layer_fp,
                    },
                )
            print()

    def pull_external_on_server(self, creds: SshCredentials, images: list[str]) -> None:
        """Deprecated: classic deploy transfers all images from the local machine."""
        if images:
            ui.warning(
                "pull_external_on_server is unused; images are built/pulled locally and pussh'd."
            )

    def prepare_remote_dirs(self, creds: SshCredentials, deploy_path: str) -> None:
        d_root = shlex.quote(deploy_path)
        d_config = shlex.quote(f"{deploy_path}/config")
        if creds.sudo_password:
            pw = shlex.quote(creds.sudo_password)
            script = f"""set -e
echo {pw} | sudo -S -p '' mkdir -p {d_root} {d_config} 2>/dev/null || true
echo {pw} | sudo -S -p '' chown -R $(whoami):$(whoami) {d_root} 2>/dev/null || true
"""
            subprocess.run(ssh_argv(creds.server, "bash"), input=script.encode(), capture_output=True)
        else:
            subprocess.run(
                ssh_argv(
                    creds.server,
                    f"sudo mkdir -p {d_root} {d_config} && "
                    f"sudo chown -R $(whoami):$(whoami) {d_root}",
                ),
                capture_output=True,
            )

    def rsync_staging(self, creds: SshCredentials, deploy_path: str, staging: Path) -> None:
        ui.step("Transferring deployment files")
        self.prepare_remote_dirs(creds, deploy_path)
        ui.substep("Syncing install directory…")
        rsync = subprocess.run(
            [
                "rsync",
                "-avz",
                "--delete",
                "-e",
                "ssh " + " ".join(shlex.quote(o) for o in ssh_common_options()),
                "--exclude",
                ".env",
                "--exclude",
                "data/",
                "--exclude",
                "firebase-cert.json",
                "--exclude",
                "updater/",
                f"{staging}/",
                f"{creds.server}:{deploy_path}/",
            ],
            capture_output=True,
            text=True,
        )
        if rsync.returncode != 0:
            ui.error("Rsync failed. Error output:")
            for line in (rsync.stderr or rsync.stdout or "").splitlines():
                print(f"    {line}")
            raise SystemExit(1)

    def copy_env_to_server(self, creds: SshCredentials, deploy_path: str) -> None:
        env_src = self._paths.deploy_env_source()
        if not env_src:
            ui.error(
                "Missing deployment/.env.prod for deploy. "
                "Create it (e.g. npm run generate:env from backend, output ../deployment/.env.prod) before deploying."
            )
            raise SystemExit(1)
        ui.substep(f"Copying {env_src.name} to server .env...")
        if (
            subprocess.run(
                scp_argv(str(env_src), f"{creds.server}:{deploy_path}/.env"),
                capture_output=True,
            ).returncode
            != 0
        ):
            ui.error("Failed to copy .env to server")
            raise SystemExit(1)

    def resolve_deploy_path_on_server(self, server: str, deploy_path: str) -> str:
        quoted = shlex.quote(deploy_path)
        r = subprocess.run(
            ssh_argv(server, f"bash -lc 'eval echo {quoted}'"),
            capture_output=True,
            text=True,
        )
        out = (r.stdout or "").strip()
        if r.returncode != 0 or not out:
            ui.error(f"Could not resolve deploy path on server: {deploy_path}")
            if r.stderr:
                print(r.stderr, file=sys.stderr)
            raise SystemExit(1)
        if "~" in out:
            ui.error(f"Deploy path did not expand on server: {out}")
            raise SystemExit(1)
        return out

    def firebase_cert_path(self) -> Path:
        if self._paths.backend_dir:
            return self._paths.backend_dir / "firebase-cert.json"
        return self._paths.deployment_root / "firebase-cert.json"

    def cleanup_remote_firebase_dir(self, creds: SshCredentials, deploy_path_resolved: str) -> None:
        d = deploy_path_resolved
        if creds.sudo_password:
            pw = shlex.quote(creds.sudo_password)
            script = f"""set -e
D={shlex.quote(d)}
C="$D/firebase-cert.json"
mkdir -p "$D" 2>/dev/null || true
if [ -d "$C" ]; then
    echo {pw} | sudo -S -p '' rm -rf "$C"
fi
echo {pw} | sudo -S -p '' chown -R "$(whoami):$(whoami)" "$D" 2>/dev/null || true
"""
            subprocess.run(ssh_argv(creds.server, "bash"), input=script.encode(), capture_output=True)
        else:
            q = shlex.quote(d)
            subprocess.run(
                ssh_argv(
                    creds.server,
                    f"D={q}; C=\"$D/firebase-cert.json\"; mkdir -p \"$D\"; "
                    f'if [ -d "$C" ]; then sudo rm -rf "$C" 2>/dev/null || rm -rf "$C"; fi; '
                    f'sudo chown -R $(whoami):$(whoami) "$D" 2>/dev/null || true',
                ),
                capture_output=True,
            )

    def _wait_firebase_loop(self, cert: Path) -> None:
        while True:
            if cert.is_file():
                return
            if cert.is_dir():
                print(
                    f"  ⚠ {cert} is a directory. Delete it and save the Firebase "
                    "service account JSON as a file at that exact path."
                )
            elif cert.exists():
                print(f"  ⚠ {cert} exists but is not a regular file.")
            else:
                print(f"  ⚠ Missing {cert} (Firebase service account JSON for FCM).")
            print("  Fix this, then press Enter to check again (Ctrl+C to abort deploy).")
            input()

    def sync_firebase_cert(self, creds: SshCredentials, deploy_path: str) -> str:
        ui.substep("Firebase service account (runtime bind-mount: firebase-cert.json)...")
        resolved = self.resolve_deploy_path_on_server(creds.server, deploy_path)
        self.cleanup_remote_firebase_dir(creds, resolved)
        cert = self.firebase_cert_path()
        self._wait_firebase_loop(cert)
        remote = f"{resolved}/firebase-cert.json"
        self.scp_firebase(creds, cert, remote)
        return resolved

    def scp_firebase(self, creds: SshCredentials, cert: Path, remote_path: str) -> None:
        ui.substep("Copying firebase-cert.json...")
        r = subprocess.run(
            scp_argv(str(cert), f"{creds.server}:{remote_path}"),
            capture_output=True,
            text=True,
        )
        if r.returncode != 0:
            ui.error("Failed to copy firebase-cert.json to server")
            print(f"  Target: {creds.server}:{remote_path}", file=sys.stderr)
            err = (r.stderr or r.stdout or "").strip()
            if err:
                for line in err.splitlines():
                    print(f"  {line}", file=sys.stderr)
            raise SystemExit(1)
        subprocess.run(
            ssh_argv(creds.server, f"chmod 600 {shlex.quote(remote_path)}"),
            capture_output=True,
        )

    def run_remote_compose_up(self, creds: SshCredentials, deploy_path_resolved: str) -> None:
        ui.step("Starting stack on server (docker compose up -d)")
        pw = creds.sudo_password
        dp = deploy_path_resolved
        remote_cmd = f"SUDO_PASSWORD={shlex.quote(pw)} DEPLOY_PATH={shlex.quote(dp)} bash -s"
        r = subprocess.run(
            ssh_argv(creds.server, remote_cmd),
            input=REMOTE_COMPOSE_UP_SCRIPT.encode(),
            text=False,
        )
        if r.returncode != 0:
            raise SystemExit(r.returncode)
