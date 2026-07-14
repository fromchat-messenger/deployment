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
    ComposeBuildPhase,
    classify_compose_images,
    fromchat_image,
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

UPDATER_IMAGE = fromchat_image("updater", "latest")


def _sync_caddy_build_files(paths: ProjectPaths, compose_components: list[str]) -> None:
    if "caddy" not in compose_components or not paths.caddy_build_dir:
        return
    if paths.caddyfile_template.is_file():
        shutil.copy2(paths.caddyfile_template, paths.caddy_build_dir / "Caddyfile")


def _prepare_staging(paths: ProjectPaths, compose_components: list[str], tag: str) -> Path:
    staging = paths.staging_dir
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    (staging / "config").mkdir()

    if compose_components:
        generate_production_compose(
            paths, components=compose_components, tag=tag, output=staging / "compose.yml"
        )
        (staging / ".fromchat-version").write_text(tag + "\n", encoding="utf-8")

        if paths.livekit_config.is_file():
            shutil.copy2(paths.livekit_config, staging / "config" / "livekit.yaml")

        if "caddy" in compose_components:
            caddyfile = staging / "Caddyfile"
            if paths.caddyfile_template.is_file():
                shutil.copy2(paths.caddyfile_template, caddyfile)

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
    p = subprocess.run(
        ["docker", "compose", "-f", "compose.yml", "config", "--format", "json"],
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


def _collect_all_pushable(
    build_phase: ComposeBuildPhase,
    paths: ProjectPaths,
    stack: list[str],
    include_updater: bool,
) -> list:
    pushable = []
    ui.step("Detecting buildable services")
    for component in stack:
        if component == "caddy":
            ps = build_phase.collect_caddy_pushable()
            if ps:
                pushable.append(ps)
                ui.substep(f"caddy: {ps.service}")
            continue
        compose_root = paths.project_root_for(component)
        if not compose_root:
            continue
        services = build_phase.list_services(compose_root)
        if not services:
            ui.warning(f"No services in {compose_root}/compose.yml")
            continue
        compose_json = build_phase.load_compose_json(compose_root)
        found = build_phase.collect_pushable(compose_json, services, compose_root)
        ui.substep(f"{component}: {', '.join(p.service for p in found) or '(none)'}")
        pushable.extend(found)

    if include_updater:
        ps = build_phase.collect_updater_pushable()
        if ps:
            pushable.append(ps)
            ui.substep(f"updater: {ps.service}")

    return pushable


def main() -> None:
    paths = ProjectPaths.from_deploy_package()
    settings = load_settings(paths, sys.argv)
    ui.banner()
    ui.info("Build locally, pull remote images on this PC, transfer via pussh")
    ui.info(f"Components: {', '.join(settings.components)}")
    ui.info(f"Image tag:  {settings.tag}")
    if paths.backend_dir:
        ui.info(f"Backend:    {paths.backend_dir}")
    if paths.web_dir:
        ui.info(f"Web:        {paths.web_dir}")

    creds = SshAuth(settings.server).authenticate()
    transfer = DeployTransfer(paths)
    deploy_resolved = transfer.resolve_deploy_path_on_server(settings.server, settings.deploy_path)

    stack = settings.compose_components
    include_updater = "updater" in settings.components

    docker_local.ensure_daemon()
    docker_local.ensure_buildx(settings.use_docker_build)

    build_phase = ComposeBuildPhase(
        paths,
        tag=settings.tag,
        platform=settings.platform,
        use_docker_build=settings.use_docker_build,
    )

    all_pushable = _collect_all_pushable(
        build_phase, paths, stack, include_updater=include_updater
    )

    if not all_pushable:
        if stack or include_updater:
            ui.error("No buildable services found for selected components")
            raise SystemExit(1)

    _sync_caddy_build_files(paths, stack)

    ui.build_banner()
    to_build = build_phase.plan_builds(all_pushable)
    build_phase.run_builds(to_build)

    staging = _prepare_staging(paths, stack, settings.tag)

    ui.deploy_banner(settings.server)
    transfer.ensure_pussh()

    images_to_transfer: list[str] = []

    if stack:
        generated = _load_generated_compose(staging)
        services = list((generated.get("services") or {}).keys())
        local_tags = local_docker_image_tags()
        fromchat_images, third_party = classify_compose_images(generated, services)

        verify_fromchat_images_local(fromchat_images, local_tags, ui)
        pull_third_party_images(third_party, platform=settings.platform)

        images_to_transfer.extend(fromchat_images)
        images_to_transfer.extend(third_party)

    if include_updater:
        local_tags = local_docker_image_tags()
        verify_fromchat_images_local([UPDATER_IMAGE], local_tags, ui)
        images_to_transfer.append(UPDATER_IMAGE)

    images_to_transfer = dedupe_preserve(images_to_transfer)
    transfer.pussh_images(creds, images_to_transfer)

    if stack:
        transfer.rsync_staging(creds, settings.deploy_path, staging)
        transfer.copy_env_prod(creds, settings.deploy_path)
        if "backend" in stack:
            deploy_resolved = transfer.sync_firebase_cert(creds, settings.deploy_path)
        transfer.run_remote_systemd(creds, deploy_resolved)

    if include_updater:
        setup_updater_remote(
            creds,
            settings.deploy_path,
            deploy_resolved,
            components=settings.components,
            paths=paths,
            git_token=settings.git_token,
            sudo_password=creds.sudo_password,
        )

    print()
    ui.success("Deployment complete!")


if __name__ == "__main__":
    main()
