"""Persist deploy CLI choices in .deploy-cache (no secrets)."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

CACHE_VERSION = 1
SETTINGS_FILENAME = "settings.json"


def settings_path(cache_root: Path) -> Path:
    return cache_root / SETTINGS_FILENAME


def load_cached_settings(cache_root: Path) -> dict[str, Any] | None:
    path = settings_path(cache_root)
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or data.get("version") != CACHE_VERSION:
        return None
    return data


def save_cached_settings(cache_root: Path, *, data: dict[str, Any]) -> None:
    cache_root.mkdir(parents=True, exist_ok=True)
    payload = {"version": CACHE_VERSION, **data}
    path = settings_path(cache_root)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def clear_cached_settings(cache_root: Path) -> bool:
    path = settings_path(cache_root)
    if path.is_file():
        path.unlink()
        return True
    return False


def settings_to_cache(settings: dict[str, Any]) -> dict[str, Any]:
    """Strip secrets; only persist deploy choices."""
    out: dict[str, Any] = {}
    for key in ("server", "deploy_path", "platform", "tag", "components"):
        val = settings.get(key)
        if val is None:
            continue
        if key == "components" and isinstance(val, list):
            out[key] = val
        elif isinstance(val, str) and val.strip():
            out[key] = val.strip()
    return out
