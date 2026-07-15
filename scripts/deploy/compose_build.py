"""Compose generation and docker compose build for deploy."""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

from deploy.paths import ProjectPaths
import deploy.ui as ui
from deploy.util import (
    compute_inputs_hash,
    dedupe_preserve,
    image_exists_locally,
    local_docker_image_tags,
    local_image_layer_fp,
    read_cached_input_hash,
    write_image_cache_fields,
)

FROMCHAT_IMAGE_SERVICES = frozenset(
    {
        "backend",
        "messaging",
        "file_storage",
        "postgres",
        "web",
        "caddy",
        "updater",
        "livekit",
        "haproxy",
    }
)
FROMCHAT_PREFIX = "fromchat/"
BUILD_COMPOSE_FILE = "compose.build.yml"


def fromchat_image(service: str, tag: str) -> str:
    if service == "updater":
        return f"{FROMCHAT_PREFIX}{service}:latest"
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

    def load_compose_json(self, compose_root: Path, compose_file: str = "compose.yml") -> dict:
        env = os.environ.copy()
        env["COMPOSE_PROFILES"] = "production"
        cmd = ["docker", "compose", "-f", compose_file]
        env_file = compose_root / ".env"
        if env_file.is_file():
            cmd.extend(["--env-file", str(env_file)])
        cmd.extend(["config", "--format", "json"])
        p = subprocess.run(
            cmd,
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

    def list_build_services(self, build_dir: Path) -> list[str]:
        compose = self.load_compose_json(build_dir, BUILD_COMPOSE_FILE)
        services = compose.get("services") or {}
        out: list[str] = []
        for name, spec in services.items():
            if name not in FROMCHAT_IMAGE_SERVICES:
                continue
            if not isinstance(spec, dict):
                continue
            if isinstance(spec.get("build"), dict):
                out.append(name)
        return out

    def _service_build_paths(self, spec: dict) -> tuple[Path, Path] | None:
        build = spec.get("build")
        if not isinstance(build, dict):
            return None
        context = Path(str(build.get("context") or ".")).resolve()
        dockerfile = Path(str(build.get("dockerfile") or "Dockerfile"))
        if not dockerfile.is_absolute():
            dockerfile = context / dockerfile
        return context, dockerfile

    def _service_build_identity(self, spec: dict) -> str:
        build = spec.get("build")
        if not isinstance(build, dict):
            return ""
        parts: list[str] = []
        args = build.get("args")
        if isinstance(args, dict):
            for key in sorted(args.keys()):
                parts.append(f"arg:{key}={args[key]}")
        elif isinstance(args, list):
            for item in sorted(str(x) for x in args):
                parts.append(f"arg:{item}")
        return "\n".join(parts)

    def _service_build_target(self, spec: dict) -> str | None:
        build = spec.get("build")
        if not isinstance(build, dict):
            return None
        target = build.get("target")
        return str(target).strip() if target else None

    def _service_input_hash(self, context: Path, dockerfile: Path, spec: dict) -> str:
        return compute_inputs_hash(
            context,
            dockerfile,
            hash_script=self._paths.input_hash_script,
            extra_material=self._service_build_identity(spec),
            target=self._service_build_target(spec),
        )

    def _partition_build_services(
        self,
        build_dir: Path,
        services: list[str],
    ) -> tuple[list[str], list[str]]:
        compose = self.load_compose_json(build_dir, BUILD_COMPOSE_FILE)
        svc_map = compose.get("services") or {}
        cache_root = self._paths.local_image_cache_dir
        need: list[str] = []
        skip: list[str] = []
        for service in services:
            spec = svc_map.get(service)
            if not isinstance(spec, dict):
                need.append(service)
                continue
            paths = self._service_build_paths(spec)
            if not paths:
                need.append(service)
                continue
            context, dockerfile = paths
            image_tag = fromchat_image(service, self._tag)
            input_hash = self._service_input_hash(context, dockerfile, spec)
            cached = read_cached_input_hash(cache_root, image_tag)
            if (
                input_hash
                and cached == input_hash
                and image_exists_locally(image_tag)
            ):
                skip.append(service)
            else:
                need.append(service)
        return need, skip

    def _update_service_cache(self, spec: dict, service: str) -> None:
        paths = self._service_build_paths(spec)
        if not paths:
            return
        context, dockerfile = paths
        image_tag = fromchat_image(service, self._tag)
        input_hash = self._service_input_hash(context, dockerfile, spec)
        if not input_hash:
            return
        fields: dict[str, str] = {"input.sha256": input_hash}
        layer_fp = local_image_layer_fp(image_tag)
        if layer_fp:
            fields["local.layer.sha256"] = layer_fp
        write_image_cache_fields(self._paths.local_image_cache_dir, image_tag, **fields)

    def run_compose_build(
        self,
        build_dir: Path,
        services: list[str],
        *,
        env_file: Path | None = None,
    ) -> list[str]:
        if not services:
            ui.success("Build skipped (no services to build)")
            return []

        need_build, skip_build = self._partition_build_services(build_dir, services)

        if skip_build:
            ui.substep(f"Build cache hit — skipping: {', '.join(skip_build)}")

        if need_build:
            ui.step(f"Building {len(need_build)} image(s) via docker compose")
            ui.substep(", ".join(need_build))
            cmd = ["docker", "compose", "-f", BUILD_COMPOSE_FILE]
            resolved_env = env_file if env_file and env_file.is_file() else build_dir / ".env"
            if resolved_env.is_file():
                cmd.extend(["--env-file", str(resolved_env)])
            cmd.extend(["build", "--pull", *need_build])
            env = os.environ.copy()
            if not self._use_docker_build:
                env["DOCKER_DEFAULT_PLATFORM"] = self._platform
            if subprocess.run(cmd, cwd=build_dir, env=env).returncode != 0:
                ui.error("docker compose build failed")
                sys.exit(1)
            ui.success(f"Build complete! {len(need_build)} image(s) built")
        elif skip_build:
            ui.success(f"Build skipped — {len(skip_build)} image(s) unchanged")

        compose = self.load_compose_json(build_dir, BUILD_COMPOSE_FILE)
        svc_map = compose.get("services") or {}
        built: list[str] = []
        for service in services:
            spec = svc_map.get(service)
            if not isinstance(spec, dict):
                continue
            if not self._service_build_paths(spec):
                continue
            self._update_service_cache(spec, service)
            built.append(fromchat_image(service, self._tag))

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


def _generate_compose(
    paths: ProjectPaths,
    *,
    components: list[str],
    tag: str,
    output: Path,
    keep_build: bool,
    include_updater: bool,
) -> None:
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
    if keep_build:
        cmd.append("--keep-build")
    if "backend" in components and paths.backend_dir:
        backend_compose = paths.backend_dir / "compose.yml"
        cmd.extend(["--backend-compose", str(backend_compose)])
        backend_prod = paths.backend_dir / "compose.prod.yml"
        if backend_prod.is_file():
            cmd.extend(["--backend-compose-prod", str(backend_prod)])
        cmd.extend(["--backend-root", str(paths.backend_dir)])
    if "frontend" in components and paths.web_dir:
        cmd.extend(["--frontend-compose", str(paths.web_dir / "compose.yml")])
        cmd.extend(["--web-root", str(paths.web_dir)])
    if include_updater:
        cmd.append("--include-updater")
        if paths.updater_dir:
            cmd.extend(["--updater-root", str(paths.updater_dir)])
    if subprocess.run(cmd).returncode != 0:
        ui.error("generate-compose.py failed")
        sys.exit(1)


def generate_build_compose(
    paths: ProjectPaths,
    *,
    components: list[str],
    tag: str,
    output: Path,
    include_updater: bool,
) -> None:
    ui.step("Generating build compose")
    _generate_compose(
        paths,
        components=components,
        tag=tag,
        output=output,
        keep_build=True,
        include_updater=include_updater,
    )


def generate_production_compose(
    paths: ProjectPaths,
    *,
    components: list[str],
    tag: str,
    output: Path,
    include_updater: bool = False,
) -> None:
    ui.step("Generating production compose.yml")
    _generate_compose(
        paths,
        components=components,
        tag=tag,
        output=output,
        keep_build=False,
        include_updater=include_updater,
    )
