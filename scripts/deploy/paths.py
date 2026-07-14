"""Resolved paths for classic deploy against sibling backend/web repos."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _find_repo_with_compose(*candidates: str) -> Path | None:
    for cand in candidates:
        if not cand:
            continue
        p = Path(cand).expanduser().resolve()
        if p.is_dir() and (p / "compose.yml").is_file():
            return p
    return None


def _find_dir(*candidates: str) -> Path | None:
    for cand in candidates:
        if not cand:
            continue
        p = Path(cand).expanduser().resolve()
        if p.is_dir():
            return p
    return None


@dataclass(frozen=True)
class ProjectPaths:
    """Deployment repo + local component source trees."""

    deployment_root: Path
    scripts_dir: Path
    backend_dir: Path | None
    web_dir: Path | None
    updater_dir: Path | None
    caddy_build_dir: Path | None
    env_file: Path
    staging_dir: Path
    local_cache_root: Path
    local_image_cache_dir: Path
    input_hash_script: Path
    livekit_config: Path
    caddy_compose: Path
    caddyfile_template: Path
    systemd_unit_template: Path
    generate_compose_script: Path

    @classmethod
    def from_deploy_package(cls) -> ProjectPaths:
        deploy_pkg = Path(__file__).resolve().parent
        scripts_dir = deploy_pkg.parent
        deployment_root = scripts_dir.parent
        parent = deployment_root.parent

        backend = _find_repo_with_compose(
            os.environ.get("FROMCHAT_BACKEND_DIR", ""),
            str(parent / "backend"),
            str(parent / "Backend"),
        )
        web = _find_repo_with_compose(
            os.environ.get("FROMCHAT_WEB_DIR", ""),
            str(parent / "Web"),
            str(parent / "web"),
        )
        updater = _find_dir(
            os.environ.get("FROMCHAT_UPDATER_DIR", ""),
            str(parent / "updater"),
        )
        caddy_build = None
        if backend:
            cand = backend / "src" / "caddy"
            if cand.is_dir() and (cand / "Dockerfile").is_file():
                caddy_build = cand

        env_file = deployment_root / ".env"
        if backend and (backend / ".env").is_file():
            env_file = backend / ".env"

        cache = deployment_root / ".deploy-cache"
        return cls(
            deployment_root=deployment_root,
            scripts_dir=scripts_dir,
            backend_dir=backend,
            web_dir=web,
            updater_dir=updater,
            caddy_build_dir=caddy_build,
            env_file=env_file,
            staging_dir=cache / "staging",
            local_cache_root=cache,
            local_image_cache_dir=cache / "images",
            input_hash_script=scripts_dir / "docker_inputs_hash.py",
            livekit_config=deployment_root / "config" / "livekit.yaml",
            caddy_compose=deployment_root / "compose" / "caddy.compose.yml",
            caddyfile_template=deployment_root / "templates" / "Caddyfile",
            systemd_unit_template=deployment_root / "templates" / "fromchat.service",
            generate_compose_script=scripts_dir / "generate-compose.py",
        )

    def compose_for(self, component: str) -> Path | None:
        if component == "backend" and self.backend_dir:
            return self.backend_dir / "compose.yml"
        if component == "frontend" and self.web_dir:
            return self.web_dir / "compose.yml"
        if component == "caddy":
            return self.caddy_compose if self.caddy_compose.is_file() else None
        return None

    def project_root_for(self, component: str) -> Path | None:
        if component == "backend":
            return self.backend_dir
        if component == "frontend":
            return self.web_dir
        if component == "caddy":
            return self.caddy_build_dir
        return None
