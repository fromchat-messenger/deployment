"""CLI entry: build locally, transfer images via pussh, rsync config — no registry pulls."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from deploy.compose_build import (  # noqa: E402
    BUILD_COMPOSE_FILE,
    ComposeBuildPhase,
    classify_compose_images,
    generate_build_compose,
    generate_production_compose,
    pull_third_party_images,
    verify_fromchat_images_local,
)
from deploy.config import load_settings  # noqa: E402
import deploy.docker_local as docker_local  # noqa: E402
from deploy.paths import ProjectPaths  # noqa: E402
from deploy.ssh_auth import SshAuth  # noqa: E402
from deploy.transfer import DeployTransfer  # noqa: E402
from deploy.updater import setup_updater_remote  # noqa: E402
import deploy.ui as ui  # noqa: E402
from deploy.util import dedupe_preserve, local_docker_image_tags  # noqa: E402


def _prepare_build_dir(
    paths: ProjectPaths,
    compose_components: list[str],
    tag: str,
    *,
    include_updater: bool,
) -> Path:
    build_dir = paths.local_cache_root / "build"
    if build_dir.exists():
        shutil.rmtree(build_dir)
    build_dir.mkdir(parents=True)

    env_src = paths.deploy_env_source()
    if env_src:
        shutil.copy2(env_src, build_dir / ".env")

    generate_build_compose(
        paths,
        components=compose_components,
        tag=tag,
        output=build_dir / BUILD_COMPOSE_FILE,
        include_updater=include_updater,
    )
    return build_dir


def _prepare_staging(
    paths: ProjectPaths,
    compose_components: list[str],
    tag: str,
    *,
    include_updater: bool,
) -> Path:
    staging = paths.staging_dir
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    if compose_components or include_updater:
        env_src = paths.deploy_env_source()
        if env_src:
            shutil.copy2(env_src, staging / ".env")

        if include_updater:
            updater_env = staging / "updater" / ".env"
            updater_env.parent.mkdir(parents=True, exist_ok=True)
            if not updater_env.is_file():
                updater_env.write_text(
                    "# Placeholder for compose validation; replaced on server deploy.\n",
                    encoding="utf-8",
                )

        generate_production_compose(
            paths,
            components=compose_components,
            tag=tag,
            output=staging / "compose.yml",
            include_updater=include_updater,
        )
        (staging / ".fromchat-version").write_text(tag + "\n", encoding="utf-8")

        if paths.systemd_unit_template.is_file():
            unit = paths.systemd_unit_template.read_text(encoding="utf-8")
            if "WorkingDirectory=" not in unit:
                unit += "\nWorkingDirectory=/opt/fromchat-server\n"
            (staging / "fromchat.service").write_text(unit, encoding="utf-8")

    return staging


def _load_generated_compose(staging: Path) -> dict:
    compose_file = staging / "compose.yml"
    if not compose_file.is_file():
        return {}
    env = os.environ.copy()
    cmd = ["docker", "compose", "-f", "compose.yml"]
    staging_env = staging / ".env"
    if staging_env.is_file():
        cmd.extend(["--env-file", str(staging_env)])
    cmd.extend(["config", "--format", "json"])
    p = subprocess.run(
        cmd,
        cwd=staging,
        capture_output=True,
        text=True,
        env=env,
    )
    if p.returncode != 0:
        ui.warning("docker compose config on staging failed; using raw YAML structure")
        import yaml

        return yaml.safe_load(compose_file.read_text(encoding="utf-8")) or {}
    return json.loads(p.stdout)


def main() -> None:
    paths = ProjectPaths.from_deploy_package()
    try:
        settings = load_settings(paths, sys.argv)
        ui.banner()
        ui.warning(
            "Updater is in active development and probably won't even work. "
            "Enter skips it by default."
        )

        stack = settings.compose_components
        include_updater = "updater" in settings.components
        if stack and "backend" in stack and not paths.deploy_env_source():
            ui.error(
                "Missing deployment/.env.prod for backend deploy. "
                "Create it (e.g. npm run generate:env from backend, output ../deployment/.env.prod) before deploying."
            )
            raise SystemExit(1)

        creds = SshAuth(settings.server).authenticate()
        transfer = DeployTransfer(paths)
        deploy_resolved = transfer.resolve_deploy_path_on_server(
            settings.server, settings.deploy_path
        )

        docker_local.ensure_daemon()
        docker_local.ensure_buildx(settings.use_docker_build)

        build_phase = ComposeBuildPhase(
            paths,
            tag=settings.tag,
            platform=settings.platform,
            use_docker_build=settings.use_docker_build,
        )

        if not stack and not include_updater:
            ui.error("No components selected")
            raise SystemExit(1)

        build_dir = _prepare_build_dir(
            paths,
            stack,
            settings.tag,
            include_updater=include_updater,
        )
        build_services = build_phase.list_build_services(build_dir)
        if not build_services:
            ui.error("No buildable services found for selected components")
            raise SystemExit(1)

        ui.step("Buildable services")
        ui.substep(", ".join(build_services))

        ui.build_banner()
        build_phase.run_compose_build(build_dir, build_services, env_file=build_dir / ".env")

        staging = _prepare_staging(
            paths,
            stack,
            settings.tag,
            include_updater=include_updater,
        )

        ui.deploy_banner(settings.server)
        transfer.ensure_pussh()

        images_to_transfer: list[str] = []

        if stack or include_updater:
            generated = _load_generated_compose(staging)
            services = list((generated.get("services") or {}).keys())
            local_tags = local_docker_image_tags()
            fromchat_images, third_party = classify_compose_images(generated, services)

            verify_fromchat_images_local(fromchat_images, local_tags, ui)
            if stack:
                pull_third_party_images(third_party, platform=settings.platform)

            images_to_transfer.extend(fromchat_images)
            if stack:
                images_to_transfer.extend(third_party)

        images_to_transfer = dedupe_preserve(images_to_transfer)
        transfer.pussh_images(creds, images_to_transfer)

        if stack or include_updater:
            transfer.rsync_staging(creds, deploy_resolved, staging)
            if stack:
                transfer.copy_env_to_server(creds, deploy_resolved)
            if include_updater:
                setup_updater_remote(
                    creds,
                    deploy_resolved,
                    components=settings.components,
                    paths=paths,
                    git_token=settings.git_token,
                )
            if "backend" in stack:
                deploy_resolved = transfer.sync_firebase_cert(creds, deploy_resolved)
            if stack or include_updater:
                transfer.run_remote_systemd(creds, deploy_resolved)

        print()
        ui.success("Deployment complete!")
    except KeyboardInterrupt:
        ui.warning("Aborted.")
        raise SystemExit(130) from None


if __name__ == "__main__":
    main()
