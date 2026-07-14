"""Parse docker-compose JSON and run image builds for selected components."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

from deploy.paths import ProjectPaths
import deploy.ui as ui
from deploy.util import (
    compute_inputs_hash,
    dedupe_preserve,
    local_docker_image_tags,
    local_image_layer_fp,
    read_file_if_exists,
    sanitize_ref,
)

FROMCHAT_IMAGE_SERVICES = frozenset(
    {"main", "messaging", "file_storage", "postgres", "web", "caddy", "updater"}
)
FROMCHAT_PREFIX = "fromchat/"


@dataclass
class PushableService:
    service: str
    image_tag: str
    dockerfile: Path
    build_context: Path
    build_target: str
    input_hash: str
    compose_root: Path


def fromchat_image(service: str, tag: str) -> str:
    return f"{FROMCHAT_PREFIX}{service}:{tag}"


class ComposeBuildPhase:
    def __init__(
        self,
        paths: ProjectPaths,
        *,
        tag: str,
        platform: str,
        use_docker_build: bool,
    ) -> None:
        self._paths = paths
        self._tag = tag
        self._platform = platform
        self._use_docker_build = use_docker_build

    def load_compose_json(self, compose_root: Path) -> dict:
        env = os.environ.copy()
        env["COMPOSE_PROFILES"] = "production"
        p = subprocess.run(
            ["docker", "compose", "-f", "compose.yml", "config", "--format", "json"],
            cwd=compose_root,
            capture_output=True,
            text=True,
            env=env,
        )
        if p.returncode != 0:
            ui.error(f"docker compose config failed in {compose_root}")
            if p.stderr:
                print(p.stderr, file=sys.stderr)
            sys.exit(1)
        return json.loads(p.stdout)

    def list_services(self, compose_root: Path) -> list[str]:
        env = os.environ.copy()
        env["COMPOSE_PROFILES"] = "production"
        p = subprocess.run(
            ["docker", "compose", "-f", "compose.yml", "config", "--services"],
            cwd=compose_root,
            capture_output=True,
            text=True,
            env=env,
        )
        if p.returncode != 0:
            return []
        return [s.strip() for s in p.stdout.splitlines() if s.strip()]

    def _make_pushable(
        self,
        *,
        service: str,
        image_tag: str,
        dockerfile: Path,
        build_context: Path,
        build_target: str,
        compose_root: Path,
    ) -> PushableService | None:
        if not dockerfile.is_file():
            ui.error(f"Could not find Dockerfile for {service} at {dockerfile}")
            sys.exit(1)
        if not self._paths.input_hash_script.is_file():
            ui.error(f"Missing {self._paths.input_hash_script}")
            sys.exit(1)
        h = compute_inputs_hash(
            build_context,
            dockerfile,
            hash_script=self._paths.input_hash_script,
        )
        if not h:
            ui.error(f"Failed to compute input hash for {service}")
            sys.exit(1)
        return PushableService(
            service=service,
            image_tag=image_tag,
            dockerfile=dockerfile,
            build_context=build_context,
            build_target=build_target,
            input_hash=h,
            compose_root=compose_root,
        )

    def collect_pushable(
        self,
        compose: dict,
        services: list[str],
        compose_root: Path,
    ) -> list[PushableService]:
        out: list[PushableService] = []
        svc_map = compose.get("services") or {}
        for service in services:
            if service not in FROMCHAT_IMAGE_SERVICES:
                continue
            spec = svc_map.get(service)
            if not isinstance(spec, dict):
                continue
            build = spec.get("build")
            if not isinstance(build, dict):
                continue
            image_tag = fromchat_image(service, self._tag)
            dockerfile_rel = (build.get("dockerfile") or "").strip()
            context_rel = (build.get("context") or ".").strip()
            build_target = (build.get("target") or "").strip()
            if context_rel in (".", "./", ""):
                build_context = compose_root
            elif context_rel == "..":
                build_context = compose_root.parent
            elif context_rel.startswith("/"):
                build_context = Path(context_rel)
            else:
                build_context = (compose_root / context_rel).resolve()
            if dockerfile_rel:
                if dockerfile_rel.startswith("/"):
                    dockerfile = Path(dockerfile_rel)
                elif (compose_root / dockerfile_rel).is_file():
                    dockerfile = compose_root / dockerfile_rel
                else:
                    dockerfile = build_context / dockerfile_rel
            else:
                dockerfile = build_context / "Dockerfile"
            ps = self._make_pushable(
                service=service,
                image_tag=image_tag,
                dockerfile=dockerfile,
                build_context=build_context,
                build_target=build_target,
                compose_root=compose_root,
            )
            if ps:
                out.append(ps)
        return out

    def collect_caddy_pushable(self) -> PushableService | None:
        caddy_dir = self._paths.caddy_build_dir
        if not caddy_dir:
            ui.error(
                "Caddy selected but backend/src/caddy not found. "
                "Set FROMCHAT_BACKEND_DIR to a backend checkout."
            )
            sys.exit(1)
        return self._make_pushable(
            service="caddy",
            image_tag=fromchat_image("caddy", self._tag),
            dockerfile=caddy_dir / "Dockerfile",
            build_context=caddy_dir,
            build_target="",
            compose_root=caddy_dir,
        )

    def collect_updater_pushable(self) -> PushableService | None:
        updater_dir = self._paths.updater_dir
        if not updater_dir:
            ui.error(
                "Updater selected but ../updater not found. Set FROMCHAT_UPDATER_DIR."
            )
            sys.exit(1)
        dockerfile = updater_dir / "Dockerfile"
        return self._make_pushable(
            service="updater",
            image_tag=fromchat_image("updater", "latest"),
            dockerfile=dockerfile,
            build_context=updater_dir,
            build_target="",
            compose_root=updater_dir,
        )

    def plan_builds(self, pushable: list[PushableService]) -> list[PushableService]:
        cache_root = self._paths.local_image_cache_dir
        cache_root.mkdir(parents=True, exist_ok=True)
        to_build: list[PushableService] = []
        for ps in pushable:
            key = sanitize_ref(ps.image_tag)
            cache_file = cache_root / key / "input.sha256"
            prev = read_file_if_exists(cache_file).strip()
            fp = local_image_layer_fp(ps.image_tag)
            if prev and prev == ps.input_hash and fp:
                continue
            to_build.append(ps)
        return to_build

    def run_builds(self, to_build: list[PushableService]) -> list[str]:
        if not to_build:
            ui.success("Build skipped (no Docker inputs changed)")
            return []
        ui.step(f"Building {len(to_build)} image(s) locally")
        for ps in to_build:
            ui.substep(f"Building {ps.service} -> {ps.image_tag}...")
            if self._use_docker_build:
                args = [
                    "docker",
                    "build",
                    "--pull",
                    "--file",
                    str(ps.dockerfile),
                    "--tag",
                    ps.image_tag,
                ]
            else:
                args = [
                    "docker",
                    "buildx",
                    "build",
                    "--pull",
                    "--platform",
                    self._platform,
                    "--file",
                    str(ps.dockerfile),
                    "--tag",
                    ps.image_tag,
                    "--output=type=docker",
                    "--provenance=false",
                    "--sbom=false",
                ]
            if ps.build_target:
                args.extend(["--target", ps.build_target])
            args.append(str(ps.build_context))
            if subprocess.run(args, cwd=ps.compose_root).returncode != 0:
                ui.error(f"Build failed for {ps.service}")
                sys.exit(1)
        built: list[str] = []
        for ps in to_build:
            key = sanitize_ref(ps.image_tag)
            d = self._paths.local_image_cache_dir / key
            d.mkdir(parents=True, exist_ok=True)
            (d / "input.sha256").write_text(ps.input_hash, encoding="utf-8")
            built.append(ps.image_tag)
        ui.success(f"Build complete! {len(built)} image(s) built")
        return built


def classify_compose_images(
    compose: dict,
    service_order: list[str],
) -> tuple[list[str], list[str]]:
    """Return (fromchat images to transfer, third-party images to transfer)."""
    services = compose.get("services") or {}
    fromchat_images: list[str] = []
    third_party: list[str] = []
    for name in service_order:
        spec = services.get(name)
        if not isinstance(spec, dict):
            continue
        image_from = (spec.get("image") or "").strip()
        if not image_from:
            continue
        if image_from.startswith(FROMCHAT_PREFIX):
            fromchat_images.append(image_from)
        else:
            third_party.append(image_from)
    return dedupe_preserve(fromchat_images), dedupe_preserve(third_party)


def images_present_locally(images: list[str], local_tags: set[str]) -> list[str]:
    return dedupe_preserve([img for img in images if img in local_tags])


def verify_fromchat_images_local(fromchat_images: list[str], local_tags: set[str], ui_mod: object) -> None:
    missing = [img for img in fromchat_images if img not in local_tags]
    if missing:
        ui_mod.error(
            "Missing locally built images: " + ", ".join(missing)
        )
        print("Build them on this machine first, then deploy.", file=sys.stderr)
        sys.exit(1)


def pull_third_party_images(images: list[str], *, platform: str | None = None) -> None:
    if not images:
        return
    import deploy.docker_local as docker_local

    docker_local.pull_images(images, platform=platform)


def generate_production_compose(
    paths: ProjectPaths,
    *,
    components: list[str],
    tag: str,
    output: Path,
) -> None:
    ui.step("Generating production compose.yml")
    cmd = [
        sys.executable,
        str(paths.generate_compose_script),
        "--tag",
        tag,
        "--components",
        ",".join(components),
        "--output",
        str(output),
    ]
    if "backend" in components and paths.backend_dir:
        cmd.extend(["--backend-compose", str(paths.backend_dir / "compose.yml")])
    if "frontend" in components and paths.web_dir:
        cmd.extend(["--frontend-compose", str(paths.web_dir / "compose.yml")])
    if "caddy" in components:
        cmd.extend(["--caddy-compose", str(paths.caddy_compose)])
    if subprocess.run(cmd).returncode != 0:
        ui.error("generate-compose.py failed")
        sys.exit(1)
