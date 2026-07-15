"""Remote auto-updater env sync (updater runs from main compose.yml)."""

from __future__ import annotations

import shlex
import subprocess
from pathlib import Path

from deploy.paths import ProjectPaths
from deploy.ssh_auth import SshCredentials, ssh_argv, ssh_common_options
import deploy.ui as ui


def write_updater_env(
    dest: Path,
    *,
    deploy_path_resolved: str,
    components: list[str],
) -> None:
    components_csv = ",".join(c for c in components if c != "updater")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(
        "\n".join(
            [
                f"COMPOSE_PROJECT_DIR={deploy_path_resolved}",
                f"FROMCHAT_COMPONENTS={components_csv}",
                "CHECK_INTERVAL_SECONDS=60",
                "DOCKERHUB_NAMESPACE=fromchat",
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
) -> None:
    ui.step("Setting up auto-updater on server")

    staging_env = paths.staging_dir / "updater" / ".env"
    write_updater_env(
        staging_env,
        deploy_path_resolved=deploy_path_resolved,
        components=components,
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

    ui.success("Updater env synced (Docker Hub polling)")
