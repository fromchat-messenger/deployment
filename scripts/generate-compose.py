#!/usr/bin/env python3
"""
Merge backend + frontend (+ optional caddy) compose files for production deploy.
Replaces build: stanzas with image: fromchat/<service>:<tag>.
"""
from __future__ import annotations

import argparse
import copy
import sys
from pathlib import Path
from typing import Any

import yaml


# Services that map to fromchat/* images when they use build:
FROMCHAT_IMAGE_SERVICES = frozenset(
    {"main", "messaging", "file_storage", "postgres", "web", "caddy"}
)

BACKEND_SERVICES = frozenset(
    {"main", "messaging", "file_storage", "livekit", "postgres"}
)
FRONTEND_SERVICES = frozenset({"web"})
CADDY_SERVICES = frozenset({"caddy"})

STRIP_KEYS = ("develop",)


def load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as fh:
        data = yaml.safe_load(fh) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: root must be a mapping")
    return data


def dump_yaml(data: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        yaml.dump(
            data,
            fh,
            default_flow_style=False,
            sort_keys=False,
            allow_unicode=True,
        )


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
) -> None:
    if "build" not in service:
        return
    if service_name not in FROMCHAT_IMAGE_SERVICES:
        del service["build"]
        return
    del service["build"]
    service["image"] = f"fromchat/{service_name}:{tag}"


def patch_livekit_volume(service: dict[str, Any]) -> None:
    volumes = service.get("volumes")
    if not volumes:
        return
    patched: list[Any] = []
    for entry in volumes:
        if isinstance(entry, str) and "src/livekit/compose.yaml" in entry:
            patched.append("./config/livekit.yaml:/etc/livekit.yaml:ro")
        else:
            patched.append(entry)
    service["volumes"] = patched


def filter_services(
    services: dict[str, Any],
    enabled: set[str],
) -> dict[str, Any]:
    return {name: cfg for name, cfg in services.items() if name in enabled}


def generate(
    *,
    backend_compose: Path | None,
    frontend_compose: Path | None,
    caddy_compose: Path | None,
    tag: str,
    components: set[str],
    output: Path,
) -> None:
    merged: dict[str, Any] = {"services": {}, "networks": {}, "volumes": {}}

    enabled: set[str] = set()
    if "backend" in components:
        enabled |= BACKEND_SERVICES
    if "frontend" in components:
        enabled |= FRONTEND_SERVICES
    if "caddy" in components:
        enabled |= CADDY_SERVICES

    sources: list[Path] = []
    if backend_compose and backend_compose.is_file():
        sources.append(backend_compose)
    if frontend_compose and frontend_compose.is_file():
        sources.append(frontend_compose)
    if caddy_compose and caddy_compose.is_file() and "caddy" in components:
        sources.append(caddy_compose)

    if not sources:
        raise SystemExit("No compose inputs found.")

    for src in sources:
        doc = load_yaml(src)
        merged = deep_merge(merged, doc)

    services: dict[str, Any] = merged.get("services") or {}
    services = filter_services(services, enabled)

    for name, cfg in services.items():
        strip_dev_keys(cfg)
        replace_build_with_image(name, cfg, tag)
        if name == "livekit":
            patch_livekit_volume(cfg)
        if name == "caddy":
            cfg.setdefault("volumes", [])
            vols = cfg["volumes"]
            if isinstance(vols, list) and not any("Caddyfile" in str(v) for v in vols):
                vols.append("./Caddyfile:/etc/caddy/Caddyfile:ro")
            cfg.setdefault("networks", ["public"])
            cfg.setdefault("depends_on", ["web"])
            cfg.setdefault("restart", "unless-stopped")
            cfg.setdefault("ports", ["80:80", "443:443"])

    if "caddy" in components and "web" in services:
        web = services["web"]
        ports = web.get("ports")
        if isinstance(ports, list):
            web["ports"] = [p for p in ports if not str(p).startswith("8301:")]

    merged["services"] = services
    if not merged.get("networks"):
        merged.pop("networks", None)
    if not merged.get("volumes"):
        merged.pop("volumes", None)

    dump_yaml(merged, output)
    print(f"Wrote {output} ({len(services)} services, tag {tag})", file=sys.stderr)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate FromChat production compose.yml")
    parser.add_argument("--tag", required=True, help="Image tag, e.g. v1.0.0")
    parser.add_argument(
        "--components",
        required=True,
        help="Comma-separated: backend,frontend,caddy",
    )
    parser.add_argument("--backend-compose", type=Path, default=None)
    parser.add_argument("--frontend-compose", type=Path, default=None)
    parser.add_argument("--caddy-compose", type=Path, default=None)
    parser.add_argument("--output", type=Path, default=Path("compose.yml"))
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    components = {c.strip().lower() for c in args.components.split(",") if c.strip()}
    generate(
        backend_compose=args.backend_compose,
        frontend_compose=args.frontend_compose,
        caddy_compose=args.caddy_compose,
        tag=args.tag,
        components=components,
        output=args.output,
    )


if __name__ == "__main__":
    main()
