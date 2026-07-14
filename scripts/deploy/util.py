"""Small helpers: hashing, dedupe, cache keys."""

from __future__ import annotations

import hashlib
import shlex
import subprocess
import sys
from pathlib import Path


def sanitize_ref(ref: str) -> str:
    s = ref.replace("/", "_").replace(":", "__").replace("@", "__at__")
    return s


def dedupe_preserve(items: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for x in items:
        if x not in seen:
            seen.add(x)
            out.append(x)
    return out


def read_file_if_exists(path: Path) -> str:
    if path.is_file():
        return path.read_text(encoding="utf-8", errors="replace")
    return ""


def image_cache_dir(cache_root: Path, image_ref: str) -> Path:
    return cache_root / sanitize_ref(image_ref)


def read_cached_input_hash(cache_root: Path, image_ref: str) -> str:
    return read_file_if_exists(image_cache_dir(cache_root, image_ref) / "input.sha256").strip()


def write_image_cache_fields(cache_root: Path, image_ref: str, **fields: str) -> None:
    d = image_cache_dir(cache_root, image_ref)
    d.mkdir(parents=True, exist_ok=True)
    for name, value in fields.items():
        (d / name).write_text(value, encoding="utf-8")


def local_image_layer_fp(image: str) -> str:
    def inspect_layers(ref: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["docker", "image", "inspect", "-f", "{{json .RootFS.Layers}}", ref],
            capture_output=True,
            text=True,
        )

    p = inspect_layers(image)
    if p.returncode != 0:
        # Docker Desktop occasionally ends up in a state where repo:tag exists in `docker images`
        # but `docker image inspect repo:tag` fails. Inspecting by content-addressed ID works.
        id_p = subprocess.run(
            ["docker", "images", "--no-trunc", "--format", "{{.ID}}", image],
            capture_output=True,
            text=True,
        )
        image_id = (id_p.stdout or "").strip()
        if not image_id:
            return ""
        p = inspect_layers(image_id)
        if p.returncode != 0:
            return ""

    return hashlib.sha256(p.stdout.encode()).hexdigest()


def remote_image_layer_fp(server: str, image: str) -> str:
    from deploy.ssh_auth import ssh_argv

    quoted = shlex.quote(image)
    remote_cmd = (
        f"docker image inspect -f '{{{{json .RootFS.Layers}}}}' {quoted} "
        f"2>/dev/null || sudo docker image inspect -f '{{{{json .RootFS.Layers}}}}' {quoted}"
    )
    p = subprocess.run(ssh_argv(server, remote_cmd), capture_output=True, text=True)
    if p.returncode != 0:
        return ""
    layers = (p.stdout or "").strip()
    if not layers:
        return ""
    return hashlib.sha256(layers.encode()).hexdigest()


def compute_inputs_hash(
    context: Path,
    dockerfile: Path,
    *,
    hash_script: Path,
    python_exe: str | None = None,
    extra_material: str = "",
) -> str:
    exe = python_exe or sys.executable
    p = subprocess.run(
        [exe, str(hash_script), "--context", str(context), "--dockerfile", str(dockerfile)],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        return ""
    base = p.stdout.strip()
    if not extra_material:
        return base
    combined = hashlib.sha256()
    combined.update(base.encode())
    combined.update(b"\n")
    combined.update(extra_material.encode())
    return combined.hexdigest()


def local_docker_image_tags() -> set[str]:
    p = subprocess.run(
        ["docker", "images", "--format", "{{.Repository}}:{{.Tag}}"],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        return set()
    return {line.strip() for line in p.stdout.splitlines() if line.strip()}


def image_exists_locally(image: str) -> bool:
    return image in local_docker_image_tags()


def read_env_file_value(path: Path, key: str) -> str:
    if not path.is_file():
        return ""
    prefix = f"{key}="
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or not line.startswith(prefix):
            continue
        val = line[len(prefix) :].strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ("\"", "'"):
            val = val[1:-1]
        return val
    return ""


def require_local_images(images: list[str], *, label: str) -> None:
    missing = [img for img in dedupe_preserve(images) if not image_exists_locally(img)]
    if missing:
        raise RuntimeError(
            f"{label} not found locally: {', '.join(missing)}\n"
            "Build or pull them on this machine first, then deploy offline."
        )

