"""Remote auto-updater env sync (updater runs from main compose.yml)."""

from __future__ import annotations

import getpass
import os
import shlex
import subprocess
from pathlib import Path

from deploy.paths import ProjectPaths
from deploy.ssh_auth import SshCredentials, ssh_argv, ssh_common_options
from deploy.util import read_env_file_value
import deploy.ui as ui

DEFAULT_BACKEND_REPO = "https://github.com/fromchat-messenger/backend.git"
DEFAULT_WEB_REPO = "https://github.com/fromchat-messenger/web.git"
DEFAULT_APP_REPO = "https://github.com/fromchat-messenger/app.git"


def _repo_urls(paths: ProjectPaths) -> tuple[str, str, str]:
    backend = os.environ.get("FROMCHAT_BACKEND_REPO", DEFAULT_BACKEND_REPO)
    web = os.environ.get("FROMCHAT_WEB_REPO", DEFAULT_WEB_REPO)
    app = os.environ.get("FROMCHAT_APP_REPO", DEFAULT_APP_REPO)
    env_src = paths.deploy_env_source()
    if env_src:
        for key, current in (
            ("FROMCHAT_BACKEND_REPO", "backend"),
            ("FROMCHAT_WEB_REPO", "web"),
            ("FROMCHAT_APP_REPO", "app"),
        ):
            val = read_env_file_value(env_src, key)
            if val:
                if current == "backend":
                    backend = val
                elif current == "web":
                    web = val
                else:
                    app = val
    return backend, web, app


def resolve_updater_token(explicit: str | None, paths: ProjectPaths) -> str:
    if explicit:
        return explicit
    env_src = paths.deploy_env_source()
    if env_src:
        token = read_env_file_value(env_src, "UPDATER_TOKEN")
        if token:
            return token
    for key in ("UPDATER_TOKEN", "GIT_TOKEN", "GITHUB_TOKEN"):
        val = os.environ.get(key, "").strip()
        if val:
            return val
    ui.step("Updater token (GitHub or Gitea)")
    print("  GitHub: https://github.com/settings/tokens/new?scopes=read:packages,repo")
    print("  Gitea:  Settings → Applications → Generate New Token")
    token = getpass.getpass("  Paste UPDATER_TOKEN (input hidden): ").strip()
    if not token:
        ui.error("UPDATER_TOKEN is required when updater is selected.")
        raise SystemExit(1)
    return token


def write_updater_env(
    dest: Path,
    *,
    deploy_path_resolved: str,
    components: list[str],
    paths: ProjectPaths,
) -> None:
    backend, web, app = _repo_urls(paths)
    components_csv = ",".join(components)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(
        "\n".join(
            [
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
    deploy_path_resolved: str,
    *,
    components: list[str],
    paths: ProjectPaths,
    git_token: str | None,
) -> None:
    ui.step("Setting up auto-updater on server")
    token = resolve_updater_token(git_token, paths)
    if not token:
        ui.error(
            "UPDATER_TOKEN is missing. Add it to deployment/.env.prod "
            "(e.g. npm run generate:env from backend, output ../deployment/.env.prod) before deploying with updater."
        )
        raise SystemExit(1)

    staging_env = paths.staging_dir / "updater" / ".env"
    write_updater_env(
        staging_env,
        deploy_path_resolved=deploy_path_resolved,
        components=components,
        paths=paths,
    )

    remote_updater = f"{deploy_path_resolved}/updater"
    ui.substep("Syncing updater/.env to server…")
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
            str(staging_env),
            f"{creds.server}:{remote_updater}/.env",
        ],
        capture_output=True,
        text=True,
    )
    if rsync.returncode != 0:
        ui.error("Failed to sync updater/.env")
        for line in (rsync.stderr or rsync.stdout or "").splitlines():
            print(f"    {line}")
        raise SystemExit(1)

    ui.success("Updater env synced (started with main stack via systemd)")
