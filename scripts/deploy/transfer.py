"""Image pussh, rsync deployment layout, Firebase cert, remote systemd."""

from __future__ import annotations

import shlex
import subprocess
import sys
from pathlib import Path

from deploy.paths import ProjectPaths
from deploy.ssh_auth import SshCredentials, scp_argv, ssh_argv, ssh_common_options
import deploy.ui as ui

UNREGISTRY_IMAGE = "ghcr.io/psviderski/unregistry"

REMOTE_SYSTEMD_SCRIPT = r"""set -e

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

mkdir -p "$REMOTE_DEPLOY_PATH/config" "$REMOTE_DEPLOY_PATH/data/prod"
cd "$REMOTE_DEPLOY_PATH"

if [ ! -f "$REMOTE_DEPLOY_PATH/.env" ]; then
    echo "⚠️  Warning: .env file not found"
fi

UNIT_SRC="$REMOTE_DEPLOY_PATH/fromchat.service"
if [ -f "$UNIT_SRC" ]; then
    # Ensure WorkingDirectory matches deploy path
    sed -i.bak "s|^WorkingDirectory=.*|WorkingDirectory=$REMOTE_DEPLOY_PATH|" "$UNIT_SRC" || true
    sudo_cmd cp -f "$UNIT_SRC" /etc/systemd/system/fromchat.service
fi

if systemctl is-active --quiet fromchat; then
    sudo_cmd systemctl stop fromchat
fi

docker compose down --remove-orphans > /dev/null 2>&1 || true

sudo_cmd systemctl daemon-reload
sudo_cmd systemctl restart fromchat

sleep 3
if ! systemctl is-active --quiet fromchat; then
    echo "❌ Service failed to start"
    sudo_cmd journalctl --no-pager -xeu fromchat -n 30
    exit 1
fi
"""


class DeployTransfer:
    def __init__(self, paths: ProjectPaths) -> None:
        self._paths = paths

    def ensure_pussh(self) -> None:
        if subprocess.run(["docker", "pussh", "--help"], capture_output=True).returncode != 0:
            ui.error("docker pussh plugin not installed")
            print("   Install: npm run install:pussh (from the backend repo)")

    def ensure_unregistry(self, creds: SshCredentials) -> None:
        """Server needs unregistry for pussh; pull locally then transfer if missing."""
        from deploy.util import image_exists_locally

        check = (
            "sudo docker images --format '{{.Repository}}:{{.Tag}}' | "
            f"grep -q '^{UNREGISTRY_IMAGE}$'"
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

    def pussh_images(self, creds: SshCredentials, images: list[str]) -> None:
        ui.step("Transferring images to server")
        if not images:
            ui.success("No images to transfer")
            return
        self.ensure_unregistry(creds)
        for image in images:
            ui.substep(f"Transferring {image}...")
            if subprocess.run(["docker", "pussh", image, creds.server]).returncode != 0:
                ui.error(f"Failed to transfer {image}")
                raise SystemExit(1)
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

    def copy_env_prod(self, creds: SshCredentials, deploy_path: str) -> None:
        candidates = []
        if self._paths.backend_dir:
            candidates.append(self._paths.backend_dir / ".env.prod")
        candidates.append(self._paths.deployment_root / ".env.prod")
        prod = next((p for p in candidates if p.is_file()), None)
        if prod:
            ui.substep("Copying .env.prod to .env...")
            if (
                subprocess.run(
                    scp_argv(str(prod), f"{creds.server}:{deploy_path}/.env"),
                    capture_output=True,
                ).returncode
                != 0
            ):
                ui.warning("Failed to copy .env.prod to .env")
        else:
            ui.warning(".env.prod not found (looked in backend and deployment roots)")

    def resolve_deploy_path_on_server(self, server: str, deploy_path: str) -> str:
        r = subprocess.run(
            [
                "ssh",
                "-o",
                "BatchMode=yes",
                *ssh_common_options(),
                server,
                f"eval echo {deploy_path}",
            ],
            capture_output=True,
            text=True,
        )
        out = r.stdout.strip()
        return out if out else deploy_path

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

    def run_remote_systemd(self, creds: SshCredentials, deploy_path_resolved: str) -> None:
        ui.step("Deploying on server")
        pw = creds.sudo_password
        dp = deploy_path_resolved
        remote_cmd = f"SUDO_PASSWORD={shlex.quote(pw)} DEPLOY_PATH={shlex.quote(dp)} bash -s"
        r = subprocess.run(
            ssh_argv(creds.server, remote_cmd),
            input=REMOTE_SYSTEMD_SCRIPT.encode(),
            text=False,
        )
        if r.returncode != 0:
            raise SystemExit(r.returncode)
