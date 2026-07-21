#!/usr/bin/env python3
"""
Merge backend (+ frontend / updater) compose for production deploy.
Uses backend compose.yml as the base; merges compose.prod.yml when caddy is selected.
Replaces build: with image: fromchat/<service>:<tag>.
"""
from __future__ import annotations

import argparse
import copy
import sys
from pathlib import Path
from typing import Any

import yaml


FROMCHAT_IMAGE_SERVICES = frozenset(
    {
        "backend",
        "messaging",
        "file_storage",
        "postgres",
        "web",
        "admin",
        "caddy",
        "updater",
        "chat_filter",
        "livekit",
        "haproxy",
    }
)

BACKEND_SERVICES = frozenset(
    {"backend", "messaging", "file_storage", "livekit", "postgres"}
)
FRONTEND_SERVICES = frozenset({"web"})
ADMIN_SERVICES = frozenset({"admin"})
UPDATER_SERVICES = frozenset({"updater"})
CHAT_FILTER_SERVICES = frozenset({"chat_filter"})
BACKEND_BUILD_SERVICES = frozenset(
    {
        "backend",
        "messaging",
        "file_storage",
        "postgres",
        "caddy",
        "livekit",
        "haproxy",
        "chat_filter",
    }
)
CADDY_STACK_SERVICES = frozenset({"caddy", "haproxy"})

STRIP_KEYS = ("develop",)


class IndentDumper(yaml.SafeDumper):
    """PyYAML dumper that indents sequence items under their mapping keys."""

    def increase_indent(self, flow: bool = False, indentless: bool = False) -> None:
        return super().increase_indent(flow, False)


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fh:
        data = yaml.safe_load(fh) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: root must be a mapping")
    return data


def dump_yaml_str(data: dict[str, Any]) -> str:
    return yaml.dump(
        data,
        Dumper=IndentDumper,
        default_flow_style=False,
        sort_keys=False,
        allow_unicode=True,
        indent=2,
    )


def dump_yaml(data: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dump_yaml_str(data), encoding="utf-8")


def deep_merge(base: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
    out = copy.deepcopy(base)
    for key, value in extra.items():
        if key in out and isinstance(out[key], dict) and isinstance(value, dict):
            out[key] = deep_merge(out[key], value)
        else:
            out[key] = copy.deepcopy(value)
    return out


def strip_dev_keys(service: dict[str, Any]) -> None:
    for key in STRIP_KEYS:
        service.pop(key, None)


def replace_build_with_image(
    service_name: str,
    service: dict[str, Any],
    tag: str,
    *,
    keep_build: bool = False,
) -> None:
    if "build" not in service:
        return
    if service_name not in FROMCHAT_IMAGE_SERVICES:
        if not keep_build:
            del service["build"]
        return
    image_tag = "latest" if service_name == "updater" else tag
    service["image"] = f"fromchat/{service_name}:{image_tag}"
    if not keep_build:
        del service["build"]


def patch_build_roots(
    services: dict[str, Any],
    *,
    backend_root: Path | None,
    web_root: Path | None,
    admin_root: Path | None,
    updater_root: Path | None,
) -> None:
    for name, cfg in services.items():
        build = cfg.get("build")
        if not isinstance(build, dict):
            continue
        if name in BACKEND_BUILD_SERVICES and backend_root:
            _rewrite_build_context(build, backend_root)
        elif name == "web" and web_root:
            _rewrite_build_context(build, web_root)
        elif name == "admin" and admin_root:
            _rewrite_build_context(build, admin_root)
        elif name == "updater" and updater_root:
            build["context"] = str(updater_root)
            build["dockerfile"] = "Dockerfile"


def _rewrite_build_context(build: dict[str, Any], root: Path) -> None:
    context_rel = (build.get("context") or ".").strip()
    dockerfile = (build.get("dockerfile") or "Dockerfile").strip()
    if context_rel in (".", "./", ""):
        build["context"] = str(root)
    elif context_rel.startswith("./"):
        build["context"] = str((root / context_rel[2:]).resolve())
    else:
        build["context"] = str((root / context_rel).resolve())
    build["dockerfile"] = dockerfile


def resolve_backend_compose_prod(
    backend_compose: Path | None,
    explicit: Path | None,
) -> Path | None:
    if explicit is not None:
        return explicit if explicit.is_file() else None
    if backend_compose is not None and backend_compose.is_file():
        candidate = backend_compose.parent / "compose.prod.yml"
        if candidate.is_file():
            return candidate
    return None


def load_updater_service(updater_root: Path) -> dict[str, Any]:
    compose_path = updater_root / "compose.yml"
    if compose_path.is_file():
        doc = load_yaml(compose_path)
        svc = (doc.get("services") or {}).get("updater")
        if isinstance(svc, dict):
            return copy.deepcopy(svc)
    return {
        "build": {"context": str(updater_root), "dockerfile": "Dockerfile"},
        "restart": "always",
    }


def load_updater_networks(updater_root: Path) -> dict[str, Any]:
    compose_path = updater_root / "compose.yml"
    if compose_path.is_file():
        doc = load_yaml(compose_path)
        nets = doc.get("networks")
        if isinstance(nets, dict):
            return copy.deepcopy(nets)
    return {"updater": {"driver": "bridge"}}


def patch_updater_service(service: dict[str, Any], *, keep_build: bool) -> None:
    service.setdefault("networks", ["updater"])
    service.setdefault("restart", "always")
    if keep_build:
        service.pop("env_file", None)
        service.pop("volumes", None)
        return
    service["env_file"] = ["updater/.env", ".env"]
    service.pop("environment", None)
    service["volumes"] = [
        "./compose.yml:/fromchat/compose.yml:rw",
        "/var/run/docker.sock:/var/run/docker.sock",
    ]
    service["pull_policy"] = "never"


def load_chat_filter_service(backend_compose: Path | None) -> dict[str, Any]:
    """Load chat_filter from backend compose (shared src/Dockerfile target)."""
    if backend_compose is not None and backend_compose.is_file():
        doc = load_yaml(backend_compose)
        svc = (doc.get("services") or {}).get("chat_filter")
        if isinstance(svc, dict):
            out = copy.deepcopy(svc)
            out.pop("profiles", None)
            out.pop("develop", None)
            return out
    return {
        "build": {
            "context": ".",
            "dockerfile": "src/Dockerfile",
            "target": "chat_filter",
        },
        "restart": "always",
        "environment": {"PORT": 8305, "DATA_DIR": "/data"},
        "networks": ["services"],
    }


def patch_chat_filter_service(service: dict[str, Any], *, keep_build: bool) -> None:
    """Attach to the backend internal services network (not a separate network)."""
    service.pop("profiles", None)
    service.pop("develop", None)
    service["networks"] = ["services"]
    service.setdefault("restart", "always")
    service.setdefault(
        "environment",
        {"PORT": 8305, "DATA_DIR": "/data"},
    )
    if not keep_build:
        service["pull_policy"] = "never"


def patch_backend_chat_filter(services: dict[str, Any], *, enabled: bool) -> None:
    """Set ENABLE_CHAT_FILTER on backend; wire depends_on when the sidecar is included."""
    backend = services.get("backend")
    if not isinstance(backend, dict):
        return
    env = backend.setdefault("environment", {})
    if isinstance(env, dict):
        env.pop("FILE_STORAGE_URL", None)
        env["ENABLE_CHAT_FILTER"] = "1" if enabled else "0"
        if not enabled:
            env.pop("CHAT_FILTER_URL", None)
    if not enabled:
        return
    deps = backend.get("depends_on")
    if deps is None:
        backend["depends_on"] = {
            "chat_filter": {"condition": "service_healthy"},
        }
    elif isinstance(deps, dict):
        deps["chat_filter"] = {"condition": "service_healthy"}
    elif isinstance(deps, list) and "chat_filter" not in deps:
        deps.append("chat_filter")


def filter_services(
    services: dict[str, Any],
    enabled: set[str],
) -> dict[str, Any]:
    return {name: cfg for name, cfg in services.items() if name in enabled}


def generate(
    *,
    backend_compose: Path | None,
    backend_compose_prod: Path | None = None,
    frontend_compose: Path | None,
    admin_compose: Path | None = None,
    tag: str,
    components: set[str],
    output: Path,
    keep_build: bool = False,
    backend_root: Path | None = None,
    web_root: Path | None = None,
    admin_root: Path | None = None,
    updater_root: Path | None = None,
    include_updater: bool = False,
    include_chat_filter: bool = False,
) -> None:
    merged: dict[str, Any] = {"services": {}, "networks": {}, "volumes": {}}

    include_caddy = "caddy" in components
    enabled: set[str] = set()
    if "backend" in components:
        enabled |= BACKEND_SERVICES
    if include_caddy:
        enabled |= CADDY_STACK_SERVICES
    if "frontend" in components:
        enabled |= FRONTEND_SERVICES
    if "admin" in components:
        enabled |= ADMIN_SERVICES
    if include_updater:
        enabled |= UPDATER_SERVICES
    if include_chat_filter:
        enabled |= CHAT_FILTER_SERVICES

    sources: list[Path] = []
    if backend_compose and backend_compose.is_file():
        sources.append(backend_compose)
    if include_caddy:
        prod_compose = resolve_backend_compose_prod(backend_compose, backend_compose_prod)
        if prod_compose is None:
            raise SystemExit(
                "caddy component selected but backend compose.prod.yml was not found "
                "(pass --backend-compose-prod or place it next to compose.yml)"
            )
        sources.append(prod_compose)
    if frontend_compose and frontend_compose.is_file():
        sources.append(frontend_compose)
    if admin_compose and admin_compose.is_file():
        sources.append(admin_compose)

    if not sources and not include_updater and not include_chat_filter:
        raise SystemExit("No compose inputs found.")

    for src in sources:
        doc = load_yaml(src)
        merged = deep_merge(merged, doc)

    services: dict[str, Any] = merged.get("services") or {}
    if include_updater and updater_root:
        services["updater"] = load_updater_service(updater_root)
        patch_updater_service(services["updater"], keep_build=keep_build)
        merged["networks"] = deep_merge(
            merged.get("networks") or {},
            load_updater_networks(updater_root),
        )

    if include_chat_filter:
        # Prefer definition already merged from backend compose; fall back to loader.
        if "chat_filter" not in services:
            services["chat_filter"] = load_chat_filter_service(backend_compose)
        patch_chat_filter_service(services["chat_filter"], keep_build=keep_build)

    if "backend" in services:
        patch_backend_chat_filter(services, enabled=include_chat_filter)

    services = filter_services(services, enabled)

    for name, cfg in services.items():
        strip_dev_keys(cfg)
        replace_build_with_image(name, cfg, tag, keep_build=keep_build)
        if name == "caddy":
            deps_list: list[str] = []
            if "web" in services:
                deps_list.append("web")
            if "admin" in services:
                deps_list.append("admin")
            if deps_list:
                deps = cfg.get("depends_on")
                if isinstance(deps, list):
                    for d in deps_list:
                        if d not in deps:
                            deps.append(d)
                elif deps is None:
                    cfg["depends_on"] = deps_list
        if name == "haproxy" and "caddy" in services:
            cfg.setdefault("depends_on", ["caddy"])

    if keep_build:
        patch_build_roots(
            services,
            backend_root=backend_root,
            web_root=web_root,
            admin_root=admin_root,
            updater_root=updater_root,
        )

    if include_caddy and "web" in services:
        web = services["web"]
        # Caddy proxies to web:80 on the public network.
        nets = web.get("networks")
        if isinstance(nets, list):
            if "public" not in nets:
                nets.append("public")
        else:
            web["networks"] = ["public"]
        ports = web.get("ports")
        if isinstance(ports, list):
            web["ports"] = [p for p in ports if not str(p).startswith("8301:")]

    if include_caddy and "admin" in services:
        admin = services["admin"]
        nets = admin.get("networks")
        if isinstance(nets, list):
            if "public" not in nets:
                nets.append("public")
        else:
            admin["networks"] = ["public"]
        ports = admin.get("ports")
        if isinstance(ports, list):
            admin["ports"] = [p for p in ports if not str(p).startswith("8306:")]

    if not keep_build:
        for cfg in services.values():
            if isinstance(cfg, dict):
                cfg["restart"] = "always"

    # Re-apply chat filter toggle after filter_services (backend may have been kept)
    if "backend" in services:
        patch_backend_chat_filter(services, enabled=include_chat_filter)

    merged["services"] = services
    if not merged.get("networks"):
        merged.pop("networks", None)
    if not merged.get("volumes"):
        merged.pop("volumes", None)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(dump_yaml_str(merged), encoding="utf-8")
    print(f"Wrote {output} ({len(services)} services, tag {tag})", file=sys.stderr)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate FromChat production compose.yml")
    parser.add_argument("--tag", required=True, help="Image tag, e.g. v1.0.0")
    parser.add_argument(
        "--components",
        required=True,
        help="Comma-separated: backend,frontend,caddy (pass --include-chat-filter for the filter sidecar)",
    )
    parser.add_argument("--backend-compose", type=Path, default=None)
    parser.add_argument("--backend-compose-prod", type=Path, default=None)
    parser.add_argument("--frontend-compose", type=Path, default=None)
    parser.add_argument("--admin-compose", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=Path("compose.yml"))
    parser.add_argument(
        "--keep-build",
        action="store_true",
        help="Keep build stanzas and set image tags (for local docker compose build)",
    )
    parser.add_argument("--backend-root", type=Path, default=None)
    parser.add_argument("--web-root", type=Path, default=None)
    parser.add_argument("--admin-root", type=Path, default=None)
    parser.add_argument("--updater-root", type=Path, default=None)
    parser.add_argument("--include-updater", action="store_true")
    parser.add_argument("--include-chat-filter", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    components = {c.strip().lower() for c in args.components.split(",") if c.strip()}
    generate(
        backend_compose=args.backend_compose,
        backend_compose_prod=args.backend_compose_prod,
        frontend_compose=args.frontend_compose,
        admin_compose=args.admin_compose,
        tag=args.tag,
        components=components,
        output=args.output,
        keep_build=args.keep_build,
        backend_root=args.backend_root,
        web_root=args.web_root,
        admin_root=args.admin_root,
        updater_root=args.updater_root,
        include_updater=args.include_updater,
        include_chat_filter=args.include_chat_filter,
    )


if __name__ == "__main__":
    main()
