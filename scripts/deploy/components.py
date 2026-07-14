"""Interactive component selection (matches deployment/scripts/lib.sh)."""

from __future__ import annotations

import shutil
import subprocess
import sys

import deploy.ui as ui

COMPONENT_OPTIONS: list[tuple[str, str, bool]] = [
    ("backend", "Backend (API, DB, LiveKit)", True),
    ("frontend", "Web frontend", True),
    ("caddy", "Caddy reverse proxy (TLS)", False),
    ("updater", "Auto-update service", False),
]

ALLOWED = {name for name, _, _ in COMPONENT_OPTIONS}


def parse_components_csv(raw: str) -> list[str]:
    out: list[str] = []
    for part in raw.split(","):
        c = part.strip().lower()
        if not c:
            continue
        if c not in ALLOWED:
            sys.stderr.write(
                f"Unknown component: {c} (allowed: {','.join(sorted(ALLOWED))})\n"
            )
            raise SystemExit(1)
        if c not in out:
            out.append(c)
    if not out:
        sys.stderr.write("Select at least one component.\n")
        raise SystemExit(1)
    return out


def _select_whiptail() -> list[str]:
    args = [
        "whiptail",
        "--title",
        "FromChat components",
        "--checklist",
        "Select components (Space toggles, Enter confirms)",
        "18",
        "72",
        str(len(COMPONENT_OPTIONS)),
    ]
    for name, label, default_on in COMPONENT_OPTIONS:
        args.extend([name, label, "ON" if default_on else "OFF"])
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode != 0:
        ui.error("Component selection cancelled.")
        raise SystemExit(1)
    selected: list[str] = []
    for token in result.stdout.split():
        name = token.strip('"')
        if name in ALLOWED:
            selected.append(name)
    if not selected:
        ui.error("Select at least one component.")
        raise SystemExit(1)
    return selected


def _select_text() -> list[str]:
    ui.warning("whiptail not found; using text menu.")
    selected: list[str] = []
    for name, label, default_on in COMPONENT_OPTIONS:
        hint = "Y/n" if default_on else "y/N"
        answer = input(f"  Include {label}? [{hint}]: ").strip()
        if not answer:
            if default_on:
                selected.append(name)
            continue
        if answer.lower().startswith("y"):
            selected.append(name)
    if not selected:
        ui.error("Select at least one component.")
        raise SystemExit(1)
    return selected


def select_components_interactive() -> list[str]:
    ui.step("Select components")
    if shutil.which("whiptail"):
        return _select_whiptail()
    return _select_text()


def compose_components(selected: list[str]) -> list[str]:
    """Components that go into the merged stack compose (excludes updater)."""
    return [c for c in selected if c != "updater"]
