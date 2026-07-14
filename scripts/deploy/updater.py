"""Remote auto-updater install (matches install.sh setup_updater)."""

from __future__ import annotations

import getpass
import os
import shlex
import shutil
import subprocess
from pathlib import Path

from deploy.paths import ProjectPaths
from deploy.ssh_auth import SshCredentials, ssh_argv, ssh_common_options
import deploy.ui as ui

DEFAULT_BACKEND_REPO = "https://github.com/fromchat-messenger/backend.git"
DEFAULT_WEB_REPO = "https://github.com/fromchat-messenger/web.git"
DEFAULT_APP_REPO = "https://github.com/fromchat-messenger/app.git"
UPDATER_IMAGE = "fromchat/updater:latest"


def _repo_urls(paths: ProjectPaths) -> tuple[str, str, str]:
    backend = os.environ.get("FROMCHAT_BACKEND_REPO", DEFAULT_BACKEND_REPO)
    web = os.environ.get("FROMCHAT_WEB_REPO", DEFAULT_WEB_REPO)
    app = os.environ.get("FROMCHAT_APP_REPO", DEFAULT_APP_REPO)
    if paths.env_file.is_file():
        for line in paths.env_file.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.partition("=")
            key = key.strip()
            val = val.strip().strip('"').strip("'")
            if key == "FROMCHAT_BACKEND_REPO" and val:
                backend = val
            elif key == "FROMCHAT_WEB_REPO" and val:
                web = val
            elif key == "FROMCHAT_APP_REPO" and val:
                app = val
    return backend, web, app


def resolve_git_token(explicit: str | None) -> str:
    if explicit:
        return explicit
    for key in ("GIT_TOKEN", "GITHUB_TOKEN", "RELEASES_TOKEN"):
        val = os.environ.get(key, "").strip()
        if val:
            return val
    ui.step("Git token for the auto-updater (GitHub or Gitea)")
    print("  GitHub: https://github.com/settings/tokens/new?scopes=read:packages,repo")
    print("  Gitea:  Settings → Applications → Generate New Token")
    token = getpass.getpass("  Paste token (input hidden): ").strip()
    if not token:
        ui.error("Git token is required when updater is selected.")
        raise SystemExit(1)
    return token


def _updater_compose_source(paths: ProjectPaths) -> Path:
    parent = paths.deployment_root.parent
    candidates = [
        paths.deployment_root.parent / "updater" / "compose.yml",
        parent / "updater" / "compose.yml",
    ]
    env_dir = os.environ.get("FROMCHAT_UPDATER_DIR", "")
    if env_dir:
        candidates.insert(0, Path(env_dir).expanduser() / "compose.yml")
    for cand in candidates:
        if cand.is_file():
            return cand
    ui.error(
        "updater/compose.yml not found. Set FROMCHAT_UPDATER_DIR or keep ../updater sibling."
    )
    raise SystemExit(1)


def write_updater_env(
    dest: Path,
    *,
    token: str,
    deploy_path_resolved: str,
    components: list[str],
    paths: ProjectPaths,
) -> None:
    backend, web, app = _repo_urls(paths)
    components_csv = ",".join(components)
    dest.write_text(
        "\n".join(
            [
                f"GITHUB_TOKEN={token}",
                f"GIT_TOKEN={token}",
                f"BACKEND_REPO={backend}",
                f"WEB_REPO={web}",
                f"DEPLOYMENT_REPO={app}",
                f"COMPOSE_PROJECT_DIR={deploy_path_resolved}",
                f"FROMCHAT_COMPONENTS={components_csv}",
                "CHECK_INTERVAL_SECONDS=60",
                "",
            ]
        ),
        encoding="utf-8",
    )


def setup_updater_remote(
    creds: SshCredentials,
    deploy_path: str,
    deploy_path_resolved: str,
    *,
    components: list[str],
    paths: ProjectPaths,
    git_token: str | None,
    sudo_password: str,
) -> None:
    ui.step("Setting up auto-updater on server")
    token = resolve_git_token(git_token)

    staging = paths.staging_dir / "updater"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    compose_src = _updater_compose_source(paths)
    shutil.copy2(compose_src, staging / "compose.yml")
    write_updater_env(
        staging / ".env",
        token=token,
        deploy_path_resolved=deploy_path_resolved,
        components=components,
        paths=paths,
    )

    remote_updater = f"{deploy_path}/updater"
    ui.substep("Syncing updater/ to server…")
    subprocess.run(
        ssh_argv(creds.server, f"mkdir -p {shlex.quote(remote_updater)}"),
        check=True,
        capture_output=True,
    )
    rsync = subprocess.run(
        [
            "rsync",
            "-avz",
            "-e",
            "ssh " + " ".join(shlex.quote(o) for o in ssh_common_options()),
            f"{staging}/",
            f"{creds.server}:{remote_updater}/",
        ],
        capture_output=True,
        text=True,
    )
    if rsync.returncode != 0:
        ui.error("Failed to sync updater directory")
        for line in (rsync.stderr or rsync.stdout or "").splitlines():
            print(f"    {line}")
        raise SystemExit(1)

    ui.substep("Starting updater service…")
    remote_cmd = (
        f"cd {shlex.quote(remote_updater)} && "
        f"COMPOSE_PROJECT_DIR={shlex.quote(deploy_path_resolved)} "
        f"docker compose --env-file .env up -d --wait --timeout 120"
    )
    if subprocess.run(ssh_argv(creds.server, remote_cmd), capture_output=True).returncode != 0:
        ui.error("Failed to start updater on server")
        raise SystemExit(1)

    ui.success("Updater service started on server")
