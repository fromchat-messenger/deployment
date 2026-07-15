"""Multi-arch image build + push to Docker Hub, GHCR, and Gitea."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import platform as py_platform
import re
import subprocess
import sys
import urllib.error
import urllib.request
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

import yaml
from rich.prompt import Prompt

from deploy.docker_local import BUILDER_NAME, ensure_buildx, ensure_daemon
from deploy.paths import ProjectPaths
import deploy.ui as ui

PLATFORMS = "linux/amd64,linux/arm64"
SEMVER_TAG = re.compile(r"^v\d+\.\d+(?:\.\d+)?$")
LATEST_TAG = "latest"
GHCR_HOST = "ghcr.io"
GITEA_HOST = "git.fromchat.ru"
DOCKERHUB_HOST = "docker.io"

# Always publish container images to GitHub Packages + Gitea unless --no-push.
DEFAULT_PUSH_REGISTRIES = ("github", "gitea")

SECRETS_DIRNAME = ".secrets"
SECRETS_FILENAME = "registry.env"
PUBLISH_SETTINGS_FILENAME = "publish.json"
PUBLISH_SETTINGS_VERSION = 1


@dataclass(frozen=True)
class ImageSpec:
    """One fromchat/* image discovered from a compose build block."""

    service: str
    repo_root: Path
    context: Path
    dockerfile: Path
    target: str | None = None
    build_args: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True)
class Registry:
    name: str
    host: str
    image_tpl: str
    user_key: str
    token_key: str
    default_user: str
    prompt_label: str


REGISTRIES: dict[str, Registry] = {
    "dockerhub": Registry(
        name="dockerhub",
        host=DOCKERHUB_HOST,
        image_tpl="fromchat/{service}",
        user_key="DOCKERHUB_USERNAME",
        token_key="DOCKERHUB_TOKEN",
        default_user="fromchat",
        prompt_label="Docker Hub",
    ),
    "github": Registry(
        name="github",
        host=GHCR_HOST,
        image_tpl="ghcr.io/fromchat-messenger/{service}",
        user_key="GHCR_USERNAME",
        token_key="GHCR_TOKEN",
        default_user="fromchat-messenger",
        prompt_label="GitHub Packages (ghcr.io)",
    ),
    "gitea": Registry(
        name="gitea",
        host=GITEA_HOST,
        image_tpl="git.fromchat.ru/fromchat/{service}",
        user_key="GITEA_USERNAME",
        token_key="GITEA_TOKEN",
        default_user="fromchat",
        prompt_label="Gitea (git.fromchat.ru)",
    ),
}


@dataclass
class Creds:
    user: str
    token: str


def secrets_dir(paths: ProjectPaths) -> Path:
    return paths.deployment_root / SECRETS_DIRNAME


def secrets_file(paths: ProjectPaths) -> Path:
    return secrets_dir(paths) / SECRETS_FILENAME


def publish_settings_file(paths: ProjectPaths) -> Path:
    return secrets_dir(paths) / PUBLISH_SETTINGS_FILENAME


def _parse_env_file(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        key = key.strip()
        val = val.strip()
        if len(val) >= 2 and val[0] == val[-1] and val[0] in ("\"", "'"):
            val = val[1:-1]
        if key:
            out[key] = val
    return out


def _quote_env_value(val: str) -> str:
    if any(ch in val for ch in ' \t#"\'\\$`'):
        esc = val.replace("\\", "\\\\").replace('"', '\\"')
        return f'"{esc}"'
    return val


def load_saved_secrets(paths: ProjectPaths) -> dict[str, str]:
    return _parse_env_file(secrets_file(paths))


def save_secrets(paths: ProjectPaths, values: dict[str, str]) -> None:
    existing = load_saved_secrets(paths)
    existing.update({k: v for k, v in values.items() if v})
    d = secrets_dir(paths)
    d.mkdir(parents=True, exist_ok=True)
    try:
        d.chmod(0o700)
    except OSError:
        pass
    lines = [
        "# FromChat publish registry credentials — do not commit",
        "# Written by deployment/scripts/deploy/publish.py",
        "",
    ]
    for key in sorted(existing):
        # Always quote token values — PATs can contain characters that break bare env parsing.
        if key.endswith("_TOKEN") or key.endswith("_PASSWORD"):
            esc = existing[key].replace("\\", "\\\\").replace('"', '\\"')
            lines.append(f'{key}="{esc}"')
        else:
            lines.append(f"{key}={_quote_env_value(existing[key])}")
    path = secrets_file(paths)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
    tmp.replace(path)
    try:
        path.chmod(0o600)
    except OSError:
        pass
    try:
        shown = path.relative_to(paths.deployment_root)
    except ValueError:
        shown = path
    ui.substep(f"Saved credentials → {shown}")


def load_publish_settings(paths: ProjectPaths) -> dict[str, object] | None:
    path = publish_settings_file(paths)
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict) or data.get("version") != PUBLISH_SETTINGS_VERSION:
        return None
    return data


def save_publish_settings(
    paths: ProjectPaths,
    *,
    services: list[str],
    registries: list[str],
) -> None:
    d = secrets_dir(paths)
    d.mkdir(parents=True, exist_ok=True)
    try:
        d.chmod(0o700)
    except OSError:
        pass
    payload = {
        "version": PUBLISH_SETTINGS_VERSION,
        "services": services,
        "registries": registries,
    }
    path = publish_settings_file(paths)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    try:
        path.chmod(0o600)
    except OSError:
        pass
    ui.substep(f"Saved publish choices → {path.relative_to(paths.deployment_root)}")


def _load_compose(path: Path) -> dict:
    with path.open(encoding="utf-8") as fh:
        data = yaml.safe_load(fh) or {}
    if not isinstance(data, dict):
        raise ValueError(f"{path}: root must be a mapping")
    return data


def _expand_compose_value(raw: str, env: Mapping[str, str] | None = None) -> str:
    """Expand Compose-style `${VAR}`, `${VAR:-default}`, and `$VAR` interpolations."""
    environ = env if env is not None else os.environ

    def repl(match: re.Match[str]) -> str:
        braced = match.group(1)
        if braced is not None:
            name = braced
            default = match.group(2)
            value = environ.get(name)
            if value is not None and value != "":
                return value
            if default is not None:
                return default
            return ""
        name = match.group(3)
        return environ.get(name, "") if name else match.group(0)

    return re.sub(
        r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}|\$([A-Za-z_][A-Za-z0-9_]*)",
        repl,
        raw,
    )


def _resolve_build_paths(compose_dir: Path, build: dict | str) -> tuple[Path, Path, str | None, tuple[tuple[str, str], ...]]:
    if isinstance(build, str):
        build = {"context": build}
    if not isinstance(build, dict):
        raise ValueError("compose build must be a mapping or string")

    context_rel = str(build.get("context") or ".").strip()
    if context_rel in (".", "./", ""):
        context = compose_dir.resolve()
    elif context_rel.startswith("./"):
        context = (compose_dir / context_rel[2:]).resolve()
    else:
        context = (compose_dir / context_rel).resolve()

    dockerfile_rel = str(build.get("dockerfile") or "Dockerfile").strip()
    dockerfile = Path(dockerfile_rel)
    if not dockerfile.is_absolute():
        dockerfile = context / dockerfile

    target = build.get("target")
    target_str = str(target).strip() if target else None

    args_raw = build.get("args") or {}
    build_args: list[tuple[str, str]] = []
    if isinstance(args_raw, dict):
        for key, val in args_raw.items():
            if val is None:
                continue
            build_args.append((str(key), _expand_compose_value(str(val))))

    return context, dockerfile.resolve(), target_str, tuple(build_args)


def discover_image_specs(paths: ProjectPaths) -> list[ImageSpec]:
    """Collect every compose service with a build: block across backend, web, updater."""
    compose_files: list[tuple[Path, Path]] = []
    if paths.backend_dir and (paths.backend_dir / "compose.yml").is_file():
        compose_files.append((paths.backend_dir, paths.backend_dir / "compose.yml"))
        prod_compose = paths.backend_dir / "compose.prod.yml"
        if prod_compose.is_file():
            compose_files.append((paths.backend_dir, prod_compose))
    if paths.web_dir and (paths.web_dir / "compose.yml").is_file():
        compose_files.append((paths.web_dir, paths.web_dir / "compose.yml"))
    if paths.updater_dir and (paths.updater_dir / "compose.yml").is_file():
        compose_files.append((paths.updater_dir, paths.updater_dir / "compose.yml"))

    if not compose_files:
        ui.error("No compose.yml found in backend, web, or updater repos.")
        raise SystemExit(1)

    specs: list[ImageSpec] = []
    seen: set[str] = set()
    for repo_root, compose_path in compose_files:
        doc = _load_compose(compose_path)
        services = doc.get("services") or {}
        if not isinstance(services, dict):
            continue
        for name, cfg in services.items():
            if not isinstance(cfg, dict) or "build" not in cfg:
                continue
            if name in seen:
                ui.error(f"Duplicate publishable service name: {name}")
                raise SystemExit(1)
            seen.add(name)
            context, dockerfile, target, build_args = _resolve_build_paths(
                compose_path.parent, cfg["build"]
            )
            specs.append(
                ImageSpec(
                    service=name,
                    repo_root=repo_root,
                    context=context,
                    dockerfile=dockerfile,
                    target=target,
                    build_args=build_args,
                )
            )

    if not specs:
        ui.error("No services with build: blocks found in compose files.")
        raise SystemExit(1)

    return sorted(specs, key=lambda s: s.service)


def _confirm(prompt: str, *, default: bool = True) -> bool:
    """Block for real TTY input — Rich Confirm can accept defaults without a keypress."""
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return default
    suffix = "Y/n" if default else "y/N"
    try:
        answer = input(f"  {prompt} [{suffix}]: ").strip().lower()
    except EOFError:
        return default
    if not answer:
        return default
    if answer in ("y", "yes"):
        return True
    if answer in ("n", "no"):
        return False
    return default


def parse_services_csv(raw: str, allowed: set[str]) -> list[str]:
    out: list[str] = []
    for part in raw.split(","):
        name = part.strip()
        if not name:
            continue
        if name not in allowed:
            ui.error(f"Unknown service: {name} (available: {', '.join(sorted(allowed))})")
            raise SystemExit(1)
        if name not in out:
            out.append(name)
    if not out:
        ui.error("Select at least one service.")
        raise SystemExit(1)
    return out


def list_all_services(specs: list[ImageSpec]) -> list[str]:
    ui.step("Services to publish")
    names: list[str] = []
    for spec in specs:
        ui.substep(f"{spec.service} ({spec.repo_root.name})")
        names.append(spec.service)
    return names


def git_tag_at_head(repo: Path) -> str:
    p = subprocess.run(
        ["git", "tag", "--points-at", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        ui.error(f"git tag --points-at HEAD failed in {repo}: {p.stderr.strip()}")
        raise SystemExit(1)
    tags = [t.strip() for t in p.stdout.splitlines() if t.strip()]
    if not tags:
        ui.error(
            f"No git tag on HEAD in {repo}. "
            "Publish tags match the component's current commit tag — create/checkout a tag first."
        )
        raise SystemExit(1)
    semver = [t for t in tags if SEMVER_TAG.match(t)]
    if semver:
        semver.sort(key=lambda t: [int(x) for x in t.lstrip("v").split(".")])
        return semver[-1]
    tags.sort()
    return tags[-1]


def git_source_url(repo: Path) -> str:
    p = subprocess.run(
        ["git", "remote", "get-url", "origin"],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        return ""
    raw = p.stdout.strip()
    if not raw:
        return ""
    if raw.startswith("git@"):
        host_path = raw[4:]
        if ":" in host_path:
            host, path = host_path.split(":", 1)
            return f"https://{host}/{path.removesuffix('.git')}"
        return ""
    if raw.startswith("http://") or raw.startswith("https://"):
        return raw.removesuffix(".git")
    return ""


def git_sha(repo: Path) -> str:
    p = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        return ""
    return p.stdout.strip()


def repo_readme(repo_root: Path) -> str:
    for name in ("README.md", "README.en.md", "README"):
        path = repo_root / name
        if path.is_file():
            return path.read_text(encoding="utf-8", errors="replace").strip()
    return ""


def readme_summary(readme: str, *, max_len: int = 100) -> str:
    if not readme:
        return ""
    for line in readme.splitlines():
        text = line.strip()
        if not text:
            continue
        if text.startswith("#"):
            text = text.lstrip("#").strip()
        if text:
            return text[:max_len]
    return ""


def _resolve_tags(specs: list[ImageSpec]) -> dict[Path, str]:
    repos: dict[Path, str] = {}
    for spec in specs:
        if spec.repo_root not in repos:
            tag = git_tag_at_head(spec.repo_root)
            repos[spec.repo_root] = tag
            ui.substep(f"{spec.repo_root.name}: {tag}")
    return repos


def _parse_push_list(raw: str | None) -> list[str] | None:
    if raw is None:
        return None
    raw = raw.strip().lower()
    if raw in ("", "none", "false", "0"):
        return []
    if raw in ("all", "true", "1"):
        return list(REGISTRIES)
    out: list[str] = []
    for part in raw.split(","):
        name = part.strip().lower()
        if not name:
            continue
        if name in ("ghcr", "github-packages", "gh"):
            name = "github"
        if name in ("hub", "docker"):
            name = "dockerhub"
        if name not in REGISTRIES:
            ui.error(
                f"Unknown registry: {part!r} "
                f"(allowed: {', '.join(sorted(REGISTRIES))}, or all)"
            )
            raise SystemExit(1)
        if name not in out:
            out.append(name)
    return out


def _registry_labels() -> dict[str, str]:
    return {
        "dockerhub": "Docker Hub (fromchat/*)",
        "github": "GitHub Packages (ghcr.io/fromchat-messenger/*)",
        "gitea": "Gitea (git.fromchat.ru/fromchat/*)",
    }


def _ensure_default_push_registries(regs: list[str]) -> list[str]:
    """GitHub + Gitea are always included when publishing (Docker Hub stays optional)."""
    out = list(regs)
    for name in DEFAULT_PUSH_REGISTRIES:
        if name not in out:
            out.append(name)
    return out


def _select_registries_interactive(saved: dict[str, object] | None) -> list[str]:
    ui.step("Push destinations")
    ui.info(
        f"Credentials are asked here and saved under {SECRETS_DIRNAME}/{SECRETS_FILENAME} "
        "(gitignored). GitHub Packages and Gitea are always included."
    )
    saved_regs = saved.get("registries") if isinstance(saved, dict) else None
    saved_set = set(saved_regs) if isinstance(saved_regs, list) else set()
    has_saved = bool(saved_set)

    selected: list[str] = list(DEFAULT_PUSH_REGISTRIES)
    labels = _registry_labels()
    for name in DEFAULT_PUSH_REGISTRIES:
        ui.substep(f"{labels[name]} — always")
    default_hub = "dockerhub" in saved_set if has_saved else False
    if _confirm(f"Also push to {labels['dockerhub']}?", default=default_hub):
        selected.append("dockerhub")
    return selected


def _saved_registries(saved: dict[str, object] | None) -> list[str]:
    if not isinstance(saved, dict):
        return []
    regs = saved.get("registries")
    if not isinstance(regs, list):
        return []
    out: list[str] = []
    for name in regs:
        if isinstance(name, str) and name in REGISTRIES and name not in out:
            out.append(name)
    return out


def _prompt_creds(
    registry: Registry,
    saved: dict[str, str],
    *,
    force_prompt: bool = False,
) -> Creds | None:
    saved_user = (
        os.environ.get(registry.user_key) or saved.get(registry.user_key) or ""
    ).strip()
    saved_token = (
        os.environ.get(registry.token_key) or saved.get(registry.token_key) or ""
    ).strip()
    default_user = saved_user or registry.default_user

    if not force_prompt and saved_user and saved_token:
        ui.substep(f"{registry.prompt_label}: using saved credentials (user={saved_user})")
        return Creds(user=saved_user, token=saved_token)

    if not sys.stdin.isatty():
        ui.warning(
            f"Missing {registry.prompt_label} credentials (no TTY) — skipping. "
            f"Set them in {SECRETS_DIRNAME}/{SECRETS_FILENAME}."
        )
        return None

    ui.substep(f"{registry.prompt_label} credentials")
    user = Prompt.ask(f"  {registry.prompt_label} username", default=default_user).strip()
    if not user:
        ui.warning(f"{registry.prompt_label} username empty — skipping")
        return None
    if not force_prompt and saved_token and saved_user == user:
        token = saved_token
        ui.substep(f"{registry.prompt_label}: reusing saved token")
    else:
        token = getpass.getpass(
            f"  {registry.prompt_label} token / password (hidden): "
        ).strip()
    if not token:
        ui.warning(f"{registry.prompt_label} token empty — skipping")
        return None
    return Creds(user=user, token=token)


def _docker_login(registry: Registry, creds: Creds) -> bool:
    ui.substep(f"docker login {registry.host} (user={creds.user})")
    p = subprocess.run(
        ["docker", "login", registry.host, "-u", creds.user, "--password-stdin"],
        input=creds.token,
        text=True,
        capture_output=True,
    )
    if p.returncode != 0:
        detail = (p.stderr or p.stdout or "").strip()
        ui.warning(f"docker login failed for {registry.host}: {detail}")
        return False
    return True


def _persist_creds(
    paths: ProjectPaths,
    saved: dict[str, str],
    registry: Registry,
    creds: Creds,
) -> None:
    save_secrets(
        paths,
        {
            registry.user_key: creds.user,
            registry.token_key: creds.token,
        },
    )
    saved[registry.user_key] = creds.user
    saved[registry.token_key] = creds.token


def collect_and_login(names: list[str], paths: ProjectPaths) -> dict[str, Creds]:
    if not names:
        return {}
    ui.step("Registry credentials")
    saved = load_saved_secrets(paths)
    secrets_path = secrets_file(paths)
    if secrets_path.is_file():
        try:
            shown = secrets_path.relative_to(paths.deployment_root)
        except ValueError:
            shown = secrets_path
        ui.info(f"Loaded saved secrets from {shown}")

    creds_map: dict[str, Creds] = {}
    skipped: list[str] = []
    for name in names:
        registry = REGISTRIES[name]
        had_saved = bool(
            (saved.get(registry.user_key) or "").strip()
            and (saved.get(registry.token_key) or "").strip()
        )
        creds = _prompt_creds(registry, saved)
        if creds is None:
            skipped.append(name)
            continue

        # Always persist before login — failed logins must still remember what was typed.
        _persist_creds(paths, saved, registry, creds)

        if _docker_login(registry, creds):
            creds_map[name] = creds
            continue

        if had_saved and sys.stdin.isatty() and _confirm(
            f"Re-enter {registry.prompt_label} credentials?",
            default=True,
        ):
            creds = _prompt_creds(registry, saved, force_prompt=True)
            if creds is not None:
                _persist_creds(paths, saved, registry, creds)
                if _docker_login(registry, creds):
                    creds_map[name] = creds
                    continue

        skipped.append(name)

    if skipped:
        ui.warning("Skipped registries after login/credential failures: " + ", ".join(skipped))
    if creds_map:
        ui.success("Logged in: " + ", ".join(creds_map))
    elif names:
        ui.error("All registry logins failed.")
        raise SystemExit(1)
    return creds_map


def _ref_for(registry: Registry, service: str, tag: str) -> str:
    return f"{registry.image_tpl.format(service=service)}:{tag}"


def _publish_tags(version_tag: str) -> list[str]:
    """Version tag plus `latest` (unless the version is already latest)."""
    if version_tag == LATEST_TAG:
        return [LATEST_TAG]
    return [version_tag, LATEST_TAG]


def _native_platform() -> str:
    machine = py_platform.machine().lower()
    if machine in ("x86_64", "amd64"):
        return "linux/amd64"
    if machine in ("aarch64", "arm64"):
        return "linux/arm64"
    return "linux/amd64"


def _oci_labels(
    spec: ImageSpec,
    tag: str,
    revision: str,
    source_url: str,
) -> list[tuple[str, str]]:
    readme = repo_readme(spec.repo_root)
    desc = readme_summary(readme) or f"FromChat {spec.service}"
    labels = [
        ("org.opencontainers.image.title", f"fromchat/{spec.service}"),
        ("org.opencontainers.image.description", desc),
        ("org.opencontainers.image.version", tag),
        ("org.opencontainers.image.vendor", "FromChat"),
    ]
    if source_url:
        labels.extend(
            [
                ("org.opencontainers.image.source", source_url),
                ("org.opencontainers.image.url", source_url),
            ]
        )
    if revision:
        labels.append(("org.opencontainers.image.revision", revision))
    return labels


def _local_image_ref(service: str, tag: str) -> str:
    return f"fromchat/{service}:{tag}"


def _local_image_exists(ref: str) -> bool:
    return (
        subprocess.run(
            ["docker", "image", "inspect", ref],
            capture_output=True,
        ).returncode
        == 0
    )


def _tag_local(src: str, dest: str) -> bool:
    p = subprocess.run(
        ["docker", "tag", src, dest],
        capture_output=True,
        text=True,
    )
    if p.returncode != 0:
        detail = (p.stderr or p.stdout or "").strip()
        ui.warning(f"docker tag {src} → {dest} failed: {detail}")
        return False
    return True


def _ensure_local_latest(service: str, version_tag: str) -> None:
    """Point fromchat/<service>:latest at the version image when present."""
    if version_tag == LATEST_TAG:
        return
    src = _local_image_ref(service, version_tag)
    dest = _local_image_ref(service, LATEST_TAG)
    if not _local_image_exists(src):
        return
    if _tag_local(src, dest):
        ui.substep(f"local → {dest}")


def _push_one_local(
    spec: ImageSpec,
    tag: str,
    *,
    push_to: list[str],
) -> list[str]:
    """Retag a local fromchat/* image and push version + latest to each registry.

    Returns the registry names that succeeded (at least one tag pushed).
    """
    local_ref = _local_image_ref(spec.service, tag)
    ui.step(f"Push {spec.service}:{tag} (+ {LATEST_TAG}) (local image, no rebuild)")
    if not _local_image_exists(local_ref):
        ui.error(
            f"Local image missing: {local_ref}. "
            "Build first (omit --no-build) or load the image into Docker."
        )
        raise SystemExit(1)
    ui.substep(f"source → {local_ref}")
    _ensure_local_latest(spec.service, tag)

    ok: list[str] = []
    for reg_name in push_to:
        registry = REGISTRIES[reg_name]
        pushed_any = False
        for t in _publish_tags(tag):
            remote = _ref_for(registry, spec.service, t)
            ui.substep(f"tag → {remote}")
            if not _tag_local(local_ref, remote):
                continue
            ui.substep(f"push → {remote}")
            if subprocess.run(["docker", "push", remote]).returncode != 0:
                ui.warning(f"docker push failed for {remote} — skipping tag")
                continue
            pushed_any = True
        if pushed_any:
            ok.append(reg_name)

    if ok:
        ui.success(f"{spec.service}:{tag} (+ {LATEST_TAG}) pushed → " + ", ".join(ok))
    else:
        ui.warning(f"{spec.service}:{tag}: no registry accepted the push")
    return ok


def _buildx_cmd(
    spec: ImageSpec,
    tags: list[str],
    *,
    revision: str,
    source_url: str,
    version_tag: str,
    platforms: str,
    mode: str,
) -> list[str]:
    """mode: 'push' | 'load' | 'cache'."""
    cmd = [
        "docker",
        "buildx",
        "build",
        "--builder",
        BUILDER_NAME,
        "--platform",
        platforms,
        "--file",
        str(spec.dockerfile),
        "--tag",
        tags[0],
    ]
    for extra in tags[1:]:
        cmd.extend(["--tag", extra])
    if spec.target:
        cmd.extend(["--target", spec.target])
    for key, val in spec.build_args:
        cmd.extend(["--build-arg", f"{key}={val}"])
    for key, val in _oci_labels(spec, version_tag, revision, source_url):
        cmd.extend(["--label", f"{key}={val}"])
        # Index annotations are only valid for multi-platform (registry) exports.
        if mode != "load":
            cmd.extend(["--annotation", f"index:{key}={val}"])
            cmd.extend(["--annotation", f"manifest:{key}={val}"])
    if mode == "push":
        cmd.append("--push")
    elif mode == "load":
        cmd.append("--load")
    else:
        cmd.extend(["--output", "type=image,push=false"])
    cmd.append(str(spec.context))
    return cmd


def _load_local_native(
    spec: ImageSpec,
    version_tag: str,
    *,
    revision: str,
    source_url: str,
) -> None:
    """Load the host platform into the local Docker engine as :version and :latest."""
    native = _native_platform()
    local_tags = [_local_image_ref(spec.service, t) for t in _publish_tags(version_tag)]
    ui.substep(f"local load ({native}) → " + ", ".join(local_tags))
    cmd = _buildx_cmd(
        spec,
        local_tags,
        revision=revision,
        source_url=source_url,
        version_tag=version_tag,
        platforms=native,
        mode="load",
    )
    if subprocess.run(cmd).returncode != 0:
        ui.warning(
            f"Could not load {spec.service} into the local Docker engine "
            f"(multi-arch push may still have succeeded)."
        )
        return
    for ref in local_tags:
        ui.substep(f"local → {ref}")


def _build_one(
    spec: ImageSpec,
    tag: str,
    *,
    push_to: list[str],
) -> list[str]:
    """Build multi-arch image; push version+latest per registry; keep native tags locally.

    Returns the registry names that were pushed successfully.
    """
    revision = git_sha(spec.repo_root)
    source_url = git_source_url(spec.repo_root)
    local_preview = ", ".join(
        _local_image_ref(spec.service, t) for t in _publish_tags(tag)
    )

    ui.step(f"Build {spec.service}:{tag} ({PLATFORMS})")
    if source_url:
        ui.substep(f"source → {source_url}")
    ui.substep(f"local targets → {local_preview}")

    if not push_to:
        # Multi-arch stays in the builder; also load native into Docker.
        cmd = _buildx_cmd(
            spec,
            [_local_image_ref(spec.service, t) for t in _publish_tags(tag)],
            revision=revision,
            source_url=source_url,
            version_tag=tag,
            platforms=PLATFORMS,
            mode="cache",
        )
        if subprocess.run(cmd).returncode != 0:
            ui.error(f"buildx failed for {spec.service}")
            raise SystemExit(1)
        _load_local_native(
            spec,
            tag,
            revision=revision,
            source_url=source_url,
        )
        ui.success(f"{spec.service}:{tag} ready locally (not pushed)")
        return []

    ok: list[str] = []
    for reg_name in push_to:
        registry = REGISTRIES[reg_name]
        remotes = [_ref_for(registry, spec.service, t) for t in _publish_tags(tag)]
        ui.substep("push → " + ", ".join(remotes))
        cmd = _buildx_cmd(
            spec,
            remotes,
            revision=revision,
            source_url=source_url,
            version_tag=tag,
            platforms=PLATFORMS,
            mode="push",
        )
        if subprocess.run(cmd).returncode != 0:
            ui.warning(
                f"buildx push failed for {registry.prompt_label} — skipping registry"
            )
            continue
        ok.append(reg_name)

    # Always leave native platform images tagged in the local engine.
    _load_local_native(
        spec,
        tag,
        revision=revision,
        source_url=source_url,
    )

    if ok:
        ui.success(
            f"{spec.service}:{tag} (+ {LATEST_TAG}) ready → " + ", ".join(ok)
        )
    else:
        ui.warning(f"{spec.service}:{tag}: build/push failed for every registry")
    return ok


def _http_json(
    method: str,
    url: str,
    *,
    headers: dict[str, str],
    body: dict | None = None,
) -> tuple[int, dict | list | None]:
    data = None
    req_headers = dict(headers)
    if body is not None:
        data = json.dumps(body).encode()
        req_headers.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode()
            parsed = json.loads(raw) if raw.strip() else None
            return resp.status, parsed
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            parsed = json.loads(raw) if raw.strip() else None
        except json.JSONDecodeError:
            parsed = {"message": raw}
        return e.code, parsed


def _dockerhub_jwt(user: str, token: str) -> str | None:
    status, data = _http_json(
        "POST",
        "https://hub.docker.com/v2/users/login/",
        headers={},
        body={"username": user, "password": token},
    )
    if status != 200 or not isinstance(data, dict):
        return None
    jwt = data.get("token")
    return jwt if isinstance(jwt, str) and jwt else None


def dockerhub_tag_exists(service: str, tag: str, jwt: str) -> bool | None:
    """Return True if the tag exists, False if not, None if the check failed."""
    status, _ = _http_json(
        "GET",
        f"https://hub.docker.com/v2/repositories/fromchat/{service}/tags/{tag}/",
        headers={"Authorization": f"JWT {jwt}"},
    )
    if status == 200:
        return True
    if status == 404:
        return False
    return None


def dockerhub_delete_tag(service: str, tag: str, jwt: str) -> bool:
    status, _ = _http_json(
        "DELETE",
        f"https://hub.docker.com/v2/repositories/fromchat/{service}/tags/{tag}/",
        headers={"Authorization": f"JWT {jwt}"},
    )
    return status in (200, 202, 204)


def verify_dockerhub_tags_free(
    specs: list[ImageSpec],
    repo_tags: dict[Path, str],
    creds: Creds,
) -> bool:
    """Ensure planned Docker Hub tags are free (or deleted with confirmation).

    Returns False if Docker Hub should be skipped (check/delete failed).
    """
    ui.step("Checking Docker Hub tags")
    jwt = _dockerhub_jwt(creds.user, creds.token)
    if not jwt:
        ui.warning("Docker Hub API login failed; skipping Docker Hub.")
        return False

    conflicts: list[tuple[str, str]] = []
    errors: list[str] = []
    seen: set[tuple[str, str]] = set()
    for spec in specs:
        tag = repo_tags[spec.repo_root]
        key = (spec.service, tag)
        if key in seen:
            continue
        seen.add(key)
        ref = f"fromchat/{spec.service}:{tag}"
        exists = dockerhub_tag_exists(spec.service, tag, jwt)
        if exists is True:
            conflicts.append(key)
            ui.substep(f"{ref} — already exists")
        elif exists is False:
            ui.substep(f"{ref} — free")
        else:
            errors.append(ref)
            ui.substep(f"{ref} — check failed")

    if errors:
        ui.warning(
            "Could not verify Docker Hub tags for: "
            + ", ".join(errors)
            + ". Skipping Docker Hub."
        )
        return False
    if conflicts:
        conflict_refs = [f"fromchat/{service}:{tag}" for service, tag in conflicts]
        ui.warning(
            "These Docker Hub tags already exist:\n  "
            + "\n  ".join(conflict_refs)
        )
        if not sys.stdin.isatty() or not sys.stdout.isatty():
            ui.warning(
                "Refusing to overwrite existing Docker Hub tags non-interactively — "
                "skipping Docker Hub."
            )
            return False
        if not _confirm(
            f"Delete all {len(conflicts)} conflicting tag(s) on Docker Hub and continue?",
            default=False,
        ):
            ui.warning("Skipping Docker Hub — conflicting tags were not deleted.")
            return False

        ui.substep("Deleting conflicting Docker Hub tags")
        failed: list[str] = []
        for service, tag in conflicts:
            ref = f"fromchat/{service}:{tag}"
            if dockerhub_delete_tag(service, tag, jwt):
                ui.substep(f"{ref} — deleted")
            else:
                failed.append(ref)
                ui.substep(f"{ref} — delete failed")

        if failed:
            ui.warning(
                "Could not delete Docker Hub tags:\n  "
                + "\n  ".join(failed)
                + "\nSkipping Docker Hub."
            )
            return False

        still_there: list[str] = []
        for service, tag in conflicts:
            if dockerhub_tag_exists(service, tag, jwt):
                still_there.append(f"fromchat/{service}:{tag}")
        if still_there:
            ui.warning(
                "Docker Hub tags still present after delete:\n  "
                + "\n  ".join(still_there)
                + "\nSkipping Docker Hub."
            )
            return False

        ui.success(f"Deleted {len(conflicts)} conflicting Docker Hub tag(s)")
        return True

    ui.success("Docker Hub tags are free")
    return True

def bind_dockerhub_repo(service: str, repo_root: Path, creds: Creds) -> None:
    jwt = _dockerhub_jwt(creds.user, creds.token)
    if not jwt:
        ui.warning(f"Docker Hub API login failed; skip repo bind for {service}")
        return
    readme = repo_readme(repo_root)
    if not readme:
        ui.warning(f"No README in {repo_root.name}; skip Docker Hub overview for {service}")
        return
    ns = "fromchat"
    desc = readme_summary(readme) or f"FromChat {service}"
    status, _ = _http_json(
        "PATCH",
        f"https://hub.docker.com/v2/repositories/{ns}/{service}/",
        headers={"Authorization": f"JWT {jwt}"},
        body={
            "description": desc[:100],
            "full_description": readme,
        },
    )
    if status in (200, 201, 202):
        ui.substep(f"Docker Hub {ns}/{service} overview ← {repo_root.name}/README")
    else:
        create_status, _ = _http_json(
            "POST",
            f"https://hub.docker.com/v2/repositories/{ns}/",
            headers={"Authorization": f"JWT {jwt}"},
            body={
                "name": service,
                "namespace": ns,
                "description": desc[:100],
                "full_description": readme,
                "is_private": False,
            },
        )
        if create_status in (200, 201, 202) or status in (200, 201, 202):
            ui.substep(f"Docker Hub {ns}/{service} overview ← {repo_root.name}/README")
        else:
            ui.warning(
                f"Could not update Docker Hub overview for {ns}/{service} "
                f"(HTTP {status}); image still has OCI source label."
            )


def bind_github_package(service: str, source_url: str, creds: Creds) -> None:
    m = re.match(r"https://github\.com/([^/]+)/([^/]+)/?$", source_url.rstrip("/"))
    if not m:
        ui.substep(f"GHCR {service}: OCI source label set")
        return
    owner, _repo = m.group(1), m.group(2)
    status, data = _http_json(
        "GET",
        f"https://api.github.com/orgs/{owner}/packages/container/{service}",
        headers={
            "Authorization": f"Bearer {creds.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    if status == 404:
        status, data = _http_json(
            "GET",
            f"https://api.github.com/users/{creds.user}/packages/container/{service}",
            headers={
                "Authorization": f"Bearer {creds.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
    if status == 200 and isinstance(data, dict):
        linked = (
            (data.get("repository") or {}).get("full_name")
            if isinstance(data.get("repository"), dict)
            else None
        )
        if linked:
            ui.substep(f"GHCR {service} linked → {linked}")
        else:
            ui.substep(f"GHCR {service}: bound via org.opencontainers.image.source={source_url}")
    else:
        ui.substep(f"GHCR {service}: source label set to {source_url}")


def bind_repos_after_push(
    specs: list[ImageSpec],
    push_to: list[str],
    creds_map: dict[str, Creds],
) -> None:
    if not push_to:
        return
    ui.step("Binding source repositories")
    seen_services: set[str] = set()
    for spec in specs:
        if spec.service in seen_services:
            continue
        seen_services.add(spec.service)
        source = git_source_url(spec.repo_root)
        if "dockerhub" in push_to and "dockerhub" in creds_map:
            bind_dockerhub_repo(spec.service, spec.repo_root, creds_map["dockerhub"])
        if "github" in push_to and "github" in creds_map:
            bind_github_package(spec.service, source, creds_map["github"])


def _parse_args(argv: list[str]) -> argparse.Namespace:
    ap = argparse.ArgumentParser(
        description=(
            "Build FromChat images for linux/amd64 + linux/arm64 and push them to "
            "GitHub Packages + Gitea (Docker Hub optional). "
            "Discovers publishable services from compose build blocks. "
            "Image tags match each repo's git tag on HEAD. "
            f"Choices are saved under {SECRETS_DIRNAME}/{PUBLISH_SETTINGS_FILENAME}."
        )
    )
    ap.add_argument(
        "--services",
        help="Comma-separated compose service names (interactive if omitted on a TTY)",
    )
    ap.add_argument(
        "--push",
        nargs="?",
        const="all",
        default=None,
        metavar="REGS",
        help=(
            "Registries to push: dockerhub,github,gitea (comma-separated), "
            "or 'all'. With bare --push, pushes to all. "
            "Omit to use saved choices / interactive prompt; GitHub + Gitea are always included."
        ),
    )
    ap.add_argument(
        "--no-push",
        action="store_true",
        help="Build only (multi-arch in buildx); do not push",
    )
    ap.add_argument(
        "--no-build",
        action="store_true",
        help=(
            "Do not build; retag and push existing local fromchat/<service>:<tag> images "
            "to the selected registries (requires --push destinations or defaults)."
        ),
    )
    return ap.parse_args(argv)


def _resolve_push_to(args: argparse.Namespace, saved: dict[str, object] | None) -> list[str]:
    if args.no_push:
        if args.no_build:
            ui.error("--no-build requires push destinations (cannot combine with --no-push).")
            raise SystemExit(1)
        return []

    parsed = _parse_push_list(args.push)
    if parsed is not None:
        if not parsed:
            return []
        push_to = parsed
    else:
        push_to = _saved_registries(saved)
        if push_to:
            ui.step("Push destinations (saved + required)")
            labels = _registry_labels()
            for name in _ensure_default_push_registries(push_to):
                if name not in push_to:
                    ui.substep(f"{labels.get(name, name)} — required (added)")
                else:
                    ui.substep(labels.get(name, name))
        elif sys.stdin.isatty() and sys.stdout.isatty():
            push_to = _select_registries_interactive(saved)
        else:
            push_to = list(DEFAULT_PUSH_REGISTRIES)
            ui.step("Push destinations (default)")
            labels = _registry_labels()
            for name in push_to:
                ui.substep(labels.get(name, name))

    return _ensure_default_push_registries(push_to)


def _run(argv: list[str] | None = None) -> None:
    args = _parse_args(argv if argv is not None else sys.argv[1:])
    paths = ProjectPaths.from_deploy_package()
    saved = load_publish_settings(paths)

    ui.build_banner()
    if args.no_build:
        ui.info("Mode: push local images only (--no-build)")
    else:
        ui.info("Platforms: " + PLATFORMS)
    ui.info("Publishable services are read from compose build: blocks in backend, web, and updater.")
    ui.info("Tags come from each source repo's git tag on HEAD.")
    ui.info("Default registries: GitHub Packages + Gitea.")
    if saved:
        ui.info(f"Loaded saved choices from {SECRETS_DIRNAME}/{PUBLISH_SETTINGS_FILENAME}")

    all_specs = discover_image_specs(paths)
    allowed = {s.service for s in all_specs}

    if args.services:
        selected_names = parse_services_csv(args.services, allowed)
    elif sys.stdin.isatty() and sys.stdout.isatty():
        selected_names = list_all_services(all_specs)
    else:
        selected_names = [s.service for s in all_specs]

    specs = [s for s in all_specs if s.service in selected_names]

    push_desired = _resolve_push_to(args, saved)

    ensure_daemon()
    if not args.no_build:
        ensure_buildx(use_compose_build=False)

    ui.step("Resolving git tags at HEAD")
    repo_tags = _resolve_tags(specs)

    creds_map = collect_and_login(push_desired, paths)
    # Only push where login succeeded.
    push_to = [name for name in push_desired if name in creds_map]

    if "dockerhub" in push_to and "dockerhub" in creds_map:
        if not verify_dockerhub_tags_free(specs, repo_tags, creds_map["dockerhub"]):
            push_to = [name for name in push_to if name != "dockerhub"]
            creds_map.pop("dockerhub", None)

    if not push_to and args.no_build:
        ui.error("No registries available to push after login/check failures.")
        raise SystemExit(1)

    if not push_to and not args.no_build:
        ui.warning(
            "No push targets — multi-arch images stay in the buildx builder "
            "(not loaded into the local Docker engine)."
        )

    pushed_any: set[str] = set()
    for spec in specs:
        if not args.no_build and not spec.dockerfile.is_file():
            ui.error(f"Dockerfile missing: {spec.dockerfile}")
            raise SystemExit(1)
        tag = repo_tags[spec.repo_root]
        if args.no_build:
            ok_regs = _push_one_local(spec, tag, push_to=push_to)
        else:
            ok_regs = _build_one(spec, tag, push_to=push_to)
        pushed_any.update(ok_regs)

    if push_to and not pushed_any:
        ui.error("Nothing was pushed to any registry.")
        raise SystemExit(1)

    bind_repos_after_push(specs, sorted(pushed_any), creds_map)

    save_publish_settings(paths, services=selected_names, registries=push_desired)

    ui.success("Publish complete" if args.no_build else "Publish build complete")
    if pushed_any:
        ui.info("Pushed to: " + ", ".join(sorted(pushed_any)))
    missed = [name for name in push_desired if name not in pushed_any]
    if missed and push_desired:
        ui.warning("Skipped / failed: " + ", ".join(missed))


def main(argv: list[str] | None = None) -> None:
    try:
        _run(argv)
    except KeyboardInterrupt:
        print(file=sys.stderr)
        ui.warning("Publish cancelled.")
        raise SystemExit(130) from None


if __name__ == "__main__":
    main()
