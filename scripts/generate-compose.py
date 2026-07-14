#!/usr/bin/env python3
"""
Merge backend (+ frontend / updater) compose for production deploy.
Uses backend compose as the source of truth (including caddy + livekit).
Replaces build: with image: fromchat/<service>:<tag>.
When caddy is not selected, the caddy service is left commented out in the file.
"""
from __future__ import annotations

import argparse
import copy
import re
import sys
from pathlib import Path
from typing import Any

import yaml


FROMCHAT_IMAGE_SERVICES = frozenset(
    {"main", "messaging", "file_storage", "postgres", "web", "caddy", "updater"}
)

BACKEND_SERVICES = frozenset(
    {"main", "messaging", "file_storage", "livekit", "postgres", "caddy", "haproxy"}
)
FRONTEND_SERVICES = frozenset({"web"})
UPDATER_SERVICES = frozenset({"updater"})
BACKEND_BUILD_SERVICES = frozenset({"main", "messaging", "file_storage", "postgres", "caddy"})
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


def load_updater_service(updater_root: Path) -> dict[str, Any]:
    compose_path = updater_root / "compose.yml"
    if compose_path.is_file():
        doc = load_yaml(compose_path)
        svc = (doc.get("services") or {}).get("updater")
        if isinstance(svc, dict):
            return copy.deepcopy(svc)
    return {
        "build": {"context": str(updater_root), "dockerfile": "Dockerfile"},
        "restart": "unless-stopped",
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
    service.setdefault("restart", "unless-stopped")
    if keep_build:
        service.pop("env_file", None)
        service.pop("volumes", None)
        return
    service["env_file"] = ["updater/.env", ".env"]
    environment = service.get("environment")
    if not isinstance(environment, dict):
        environment = {}
    environment["UPDATER_TOKEN"] = "${UPDATER_TOKEN}"
    service["environment"] = environment
    service["volumes"] = [
        "./compose.yml:/fromchat/compose.yml:rw",
        "/var/run/docker.sock:/var/run/docker.sock",
    ]
    service["extra_hosts"] = ["host.docker.internal:host-gateway"]
    service["pull_policy"] = "never"


def filter_services(
    services: dict[str, Any],
    enabled: set[str],
) -> dict[str, Any]:
    return {name: cfg for name, cfg in services.items() if name in enabled}


def comment_out_service(yaml_text: str, service_name: str) -> str:
    """Comment out a top-level service block (two-space indent under services:)."""
    lines = yaml_text.splitlines(keepends=True)
    out: list[str] = []
    commenting = False
    header = re.compile(rf"^  {re.escape(service_name)}:\s*(#.*)?$")
    next_svc = re.compile(r"^  [A-Za-z0-9_-]+:\s*(#.*)?$")
    top_key = re.compile(r"^[A-Za-z0-9_-]+:\s*(#.*)?$")

    for line in lines:
        if not commenting and header.match(line.rstrip("\n")):
            commenting = True
            out.append(f"# {line}" if not line.startswith("#") else line)
            continue
        if commenting:
            if next_svc.match(line.rstrip("\n")) or top_key.match(line.rstrip("\n")):
                commenting = False
                out.append(line)
                continue
            if line.startswith("#") or line.strip() == "":
                out.append(line)
            else:
                out.append(f"# {line}" if not line.startswith("#") else line)
            continue
        out.append(line)
    return "".join(out)


def generate(
    *,
    backend_compose: Path | None,
    frontend_compose: Path | None,
    tag: str,
    components: set[str],
    output: Path,
    keep_build: bool = False,
    backend_root: Path | None = None,
    web_root: Path | None = None,
    updater_root: Path | None = None,
    include_updater: bool = False,
) -> None:
    merged: dict[str, Any] = {"services": {}, "networks": {}, "volumes": {}}

    include_caddy = "caddy" in components
    enabled: set[str] = set()
    if "backend" in components:
        enabled |= BACKEND_SERVICES
        if not include_caddy:
            enabled -= CADDY_STACK_SERVICES
    if "frontend" in components:
        enabled |= FRONTEND_SERVICES
    if include_updater:
        enabled |= UPDATER_SERVICES

    # Keep caddy/haproxy in the dumped file so we can comment them out (production).
    comment_caddy = (
        not keep_build
        and "backend" in components
        and not include_caddy
    )
    if comment_caddy:
        enabled |= CADDY_STACK_SERVICES

    sources: list[Path] = []
    if backend_compose and backend_compose.is_file():
        sources.append(backend_compose)
    if frontend_compose and frontend_compose.is_file():
        sources.append(frontend_compose)

    if not sources and not include_updater:
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

    services = filter_services(services, enabled)

    for name, cfg in services.items():
        strip_dev_keys(cfg)
        replace_build_with_image(name, cfg, tag, keep_build=keep_build)
        if name == "caddy" and "web" in services:
            deps = cfg.get("depends_on")
            if isinstance(deps, list) and "web" not in deps:
                deps.append("web")
            elif deps is None:
                cfg["depends_on"] = ["web"]
        if name == "haproxy" and "caddy" in services:
            cfg.setdefault("depends_on", ["caddy"])

    if keep_build:
        patch_build_roots(
            services,
            backend_root=backend_root,
            web_root=web_root,
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

    if not keep_build:
        for cfg in services.values():
            if isinstance(cfg, dict):
                cfg["restart"] = "always"

    merged["services"] = services
    if not merged.get("networks"):
        merged.pop("networks", None)
    if not merged.get("volumes"):
        merged.pop("volumes", None)

    text = dump_yaml_str(merged)
    if comment_caddy:
        commented = 0
        for svc in ("haproxy", "caddy"):
            if svc in services:
                text = comment_out_service(text, svc)
                commented += 1
        active = len(services) - commented
    else:
        active = len(services)

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(text, encoding="utf-8")
    print(f"Wrote {output} ({active} services, tag {tag})", file=sys.stderr)


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
    parser.add_argument("--output", type=Path, default=Path("compose.yml"))
    parser.add_argument(
        "--keep-build",
        action="store_true",
        help="Keep build stanzas and set image tags (for local docker compose build)",
    )
    parser.add_argument("--backend-root", type=Path, default=None)
    parser.add_argument("--web-root", type=Path, default=None)
    parser.add_argument("--updater-root", type=Path, default=None)
    parser.add_argument("--include-updater", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    components = {c.strip().lower() for c in args.components.split(",") if c.strip()}
    generate(
        backend_compose=args.backend_compose,
        frontend_compose=args.frontend_compose,
        tag=args.tag,
        components=components,
        output=args.output,
        keep_build=args.keep_build,
        backend_root=args.backend_root,
        web_root=args.web_root,
        updater_root=args.updater_root,
        include_updater=args.include_updater,
    )


if __name__ == "__main__":
    main()
