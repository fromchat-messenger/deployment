"""Interactive component selection (matches deployment/scripts/lib.sh)."""

from __future__ import annotations

import sys

from rich.prompt import Confirm

import deploy.ui as ui

COMPONENT_OPTIONS: list[tuple[str, str, bool]] = [
    ("backend", "Backend (API, DB, LiveKit)", True),
    ("frontend", "Web frontend", True),
    ("caddy", "Caddy reverse proxy (TLS)", True),
    # Updater is opt-in — Enter / default skips it.
    ("updater", "Auto-update service", False),
    # Chat filter is opt-in; without it deploy sets ENABLE_CHAT_FILTER=0 on the backend.
    ("chat_filter", "Chat content filter service", False),
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


def select_components_interactive() -> list[str]:
    ui.step("Select components")
    ui.warning(
        "Updater is in active development and probably won't even work. "
        "Enter skips it by default."
    )
    selected: list[str] = []
    try:
        for name, label, default_on in COMPONENT_OPTIONS:
            if Confirm.ask(f"  Include {label}?", default=default_on):
                selected.append(name)
    except KeyboardInterrupt:
        raise
    if not selected:
        ui.error("Select at least one component.")
        raise SystemExit(1)
    return selected


def compose_components(selected: list[str]) -> list[str]:
    """Components that go into the merged stack compose (excludes updater / chat_filter flags)."""
    return [c for c in selected if c not in {"updater", "chat_filter"}]
