"""Load .env and CLI into settings."""

from __future__ import annotations

import os
import platform
import sys
from dataclasses import dataclass

from dotenv import load_dotenv

from deploy.components import compose_components, parse_components_csv, select_components_interactive
from deploy.paths import ProjectPaths
from deploy.session_cache import (
    clear_cached_settings,
    load_cached_settings,
    save_cached_settings,
    settings_to_cache,
)
import deploy.ui as ui


@dataclass
class DeploySettings:
    server: str
    deploy_path: str
    platform: str
    host_arch: str
    platform_arch: str
    use_docker_build: bool
    components: list[str]
    compose_components: list[str]
    tag: str
    paths: ProjectPaths


def _machine_arch() -> str:
    m = platform.machine().lower()
    if m in ("arm64", "aarch64"):
        return "arm64"
    if m in ("x86_64", "amd64", "i386", "i686"):
        return "amd64"
    return m


def _components_from_cache(cached: dict) -> str | None:
    raw = cached.get("components")
    if isinstance(raw, list):
        parts = [str(c).strip().lower() for c in raw if str(c).strip()]
        return ",".join(parts) if parts else None
    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    return None


def _persist_partial(
    paths: ProjectPaths,
    *,
    server: str = "",
    deploy_path: str = "",
    platform: str = "",
    tag: str = "",
    components: list[str] | None = None,
) -> None:
    data = settings_to_cache(
        {
            "server": server,
            "deploy_path": deploy_path,
            "platform": platform,
            "tag": tag,
            "components": components or [],
        }
    )
    if not data:
        return
    save_cached_settings(paths.local_cache_root, data=data)


def load_settings(paths: ProjectPaths, argv: list[str]) -> DeploySettings:
    reset_cache = False
    if paths.env_file.is_file():
        load_dotenv(paths.env_file, override=False)

    positional: list[str] = []
    components_raw: str | None = None
    tag: str | None = None
    interactive = True
    i = 1
    while i < len(argv):
        arg = argv[i]
        if arg == "--reset":
            reset_cache = True
            i += 1
            continue
        if arg in ("--components", "-c"):
            if i + 1 >= len(argv):
                sys.stderr.write("--components requires a value\n")
                raise SystemExit(1)
            components_raw = argv[i + 1]
            interactive = False
            i += 2
            continue
        if arg in ("--tag", "-t"):
            if i + 1 >= len(argv):
                sys.stderr.write("--tag requires a value\n")
                raise SystemExit(1)
            tag = argv[i + 1]
            i += 2
            continue
        if arg in ("--no-interactive",):
            interactive = False
            i += 1
            continue
        if arg in ("-h", "--help"):
            sys.stdout.write(
                "Usage: deploy.sh [user@host] [deploy_path] [platform] "
                "[--components backend,frontend,caddy,updater,chat_filter] [--tag TAG]\n"
                "  Interactive component menu when --components is omitted.\n"
                "  Or set DEPLOYMENT_SERVER in .env\n"
                "  Paths: FROMCHAT_BACKEND_DIR, FROMCHAT_WEB_DIR, FROMCHAT_UPDATER_DIR\n"
                "  Saved choices: .deploy-cache/settings.json (--reset to clear)\n"
            )
            raise SystemExit(0)
        if arg.startswith("-"):
            sys.stderr.write(f"Unknown option: {arg}\n")
            raise SystemExit(1)
        positional.append(arg)
        i += 1

    if reset_cache:
        if clear_cached_settings(paths.local_cache_root):
            ui.info("Cleared saved deploy settings.")
        else:
            ui.info("No saved deploy settings to clear.")

    cached = None if reset_cache else load_cached_settings(paths.local_cache_root)

    if tag is None:
        tag = os.environ.get("FROMCHAT_TAG") or (cached or {}).get("tag") or "latest"

    if components_raw is None:
        components_raw = os.environ.get("FROMCHAT_COMPONENTS") or _components_from_cache(
            cached or {}
        )

    server = (positional[0] if positional else None) or os.environ.get(
        "DEPLOYMENT_SERVER", ""
    ) or str((cached or {}).get("server") or "")
    server = server.strip()

    deploy_path = (
        (positional[1] if len(positional) > 1 else None)
        or os.environ.get("DEPLOYMENT_PATH", "")
        or str((cached or {}).get("deploy_path") or "")
        or "~/fromchat-server"
    ).strip()

    docker_platform = (
        (positional[2] if len(positional) > 2 else None)
        or os.environ.get("DEPLOYMENT_PLATFORM", "")
        or str((cached or {}).get("platform") or "")
        or "linux/arm64"
    ).strip()

    if not server:
        sys.stderr.write(
            "Server not specified. Usage: deploy.sh [user@host] [deploy_path] [platform]\n"
            f"   Or set DEPLOYMENT_SERVER in {paths.env_file} or as an environment variable\n\n"
            "Example:\n"
            "  deploy.sh user@example.com ~/fromchat-server linux/arm64 --tag latest\n"
        )
        raise SystemExit(1)

    try:
        if components_raw is not None:
            components = parse_components_csv(components_raw)
        elif interactive and sys.stdin.isatty():
            components = select_components_interactive()
        else:
            components = parse_components_csv("backend,frontend")
    except KeyboardInterrupt:
        _persist_partial(
            paths,
            server=server,
            deploy_path=deploy_path,
            platform=docker_platform,
            tag=tag,
        )
        ui.warning("Aborted.")
        raise SystemExit(130) from None

    stack = compose_components(components)

    host_arch = _machine_arch()
    platform_arch = docker_platform.split("/", 1)[-1]
    use_docker_build = bool(host_arch and host_arch == platform_arch)

    if "backend" in components and not paths.backend_dir:
        sys.stderr.write(
            "Backend component selected but backend repo not found.\n"
            "Set FROMCHAT_BACKEND_DIR or keep a sibling ../backend with compose.yml.\n"
        )
        raise SystemExit(1)
    if "frontend" in components and not paths.web_dir:
        sys.stderr.write(
            "Frontend component selected but web repo not found.\n"
            "Set FROMCHAT_WEB_DIR or keep a sibling ../Web with compose.yml.\n"
        )
        raise SystemExit(1)
    if "caddy" in components and not paths.caddy_build_dir:
        sys.stderr.write(
            "Caddy component selected but backend/src/caddy not found.\n"
            "Set FROMCHAT_BACKEND_DIR to a backend checkout.\n"
        )
        raise SystemExit(1)
    if "updater" in components and not paths.updater_dir:
        sys.stderr.write(
            "Updater component selected but ../updater not found.\n"
            "Set FROMCHAT_UPDATER_DIR to the updater repo.\n"
        )
        raise SystemExit(1)
    if "chat_filter" in components and "backend" not in components:
        sys.stderr.write(
            "Chat filter requires the backend component "
            "(built from backend src/Dockerfile target chat_filter).\n"
        )
        raise SystemExit(1)

    settings = DeploySettings(
        server=server,
        deploy_path=deploy_path,
        platform=docker_platform,
        host_arch=host_arch,
        platform_arch=platform_arch,
        use_docker_build=use_docker_build,
        components=components,
        compose_components=stack,
        tag=tag,
        paths=paths,
    )

    save_cached_settings(
        paths.local_cache_root,
        data=settings_to_cache(
            {
                "server": settings.server,
                "deploy_path": settings.deploy_path,
                "platform": settings.platform,
                "tag": settings.tag,
                "components": settings.components,
            }
        ),
    )

    return settings
