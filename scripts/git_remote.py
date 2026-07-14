#!/usr/bin/env python3
"""
GitHub + Gitea (git.fromchat.ru) helpers for FromChat deployment/updater.

Official GitHub: github.com/fromchat-messenger/{backend,web,app}
Gitea:           git.fromchat.ru/FromChat/{same repo name}

Official GitHub URLs fall back to Gitea when GitHub fails.
URLs already on git.fromchat.ru use Gitea APIs directly.

Gitea on the same host is preferred via Docker host-gateway :3000 (plain HTTP),
because public https://git.fromchat.ru goes through Caddy TLS and can fail from
containers when certs are unavailable or hairpin routing breaks.
"""
from __future__ import annotations

import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

SEMVER = re.compile(r"^v(\d+)\.(\d+)(?:\.(\d+))?$")

GITHUB_ORG = "fromchat-messenger"
GITEA_ORG = "FromChat"
GITHUB_OFFICIAL_PREFIX = f"https://github.com/{GITHUB_ORG}/"
GITEA_PUBLIC_HOST = "git.fromchat.ru"
GITEA_PUBLIC_BASE = f"https://{GITEA_PUBLIC_HOST}"
# Kept for callers / docs; resolved dynamically via gitea_base_candidates().
GITEA_BASE = GITEA_PUBLIC_BASE

OFFICIAL_GITHUB_REPOS = frozenset(
    {
        f"https://github.com/{GITHUB_ORG}/backend.git",
        f"https://github.com/{GITHUB_ORG}/web.git",
        f"https://github.com/{GITHUB_ORG}/app.git",
    }
)

_gitea_working_base: str | None = None


def normalize_repo_url(url: str) -> str:
    u = url.rstrip("/")
    if u.endswith(".git"):
        return u
    return u + ".git"


def is_gitea_repo(url: str) -> bool:
    return "git.fromchat.ru" in normalize_repo_url(url)


def is_official_github_repo(url: str) -> bool:
    normalized = normalize_repo_url(url)
    return normalized in OFFICIAL_GITHUB_REPOS or GITHUB_OFFICIAL_PREFIX in normalized


def gitea_owner_repo(github_owner: str, repo: str) -> tuple[str, str]:
    """Map github.com/fromchat-messenger/{repo} -> git.fromchat.ru/FromChat/{repo}."""
    if github_owner == GITEA_ORG:
        return github_owner, repo
    if github_owner == GITHUB_ORG or is_official_github_repo(
        f"https://github.com/{github_owner}/{repo}.git"
    ):
        return GITEA_ORG, repo
    return github_owner, repo


def parse_slug(url: str) -> tuple[str, str]:
    u = url.rstrip("/").removesuffix(".git")
    for marker in ("github.com/", "git.fromchat.ru/"):
        if marker in u:
            slug = u.split(marker, 1)[1]
            owner, repo = slug.split("/", 1)
            return owner, repo
    if "/" in u and "://" not in u:
        owner, repo = u.split("/", 1)
        return owner, repo
    raise ValueError(f"Cannot parse repository slug from {url!r}")


def _normalize_token(token: str | None) -> str | None:
    if not token:
        return None
    cleaned = token.strip()
    return cleaned or None


def gitea_base_candidates() -> list[str]:
    """Prefer same-host Gitea (:3000) before public HTTPS (Caddy TLS)."""
    out: list[str] = []
    env = os.environ.get("GITEA_BASE", "").strip().rstrip("/")
    if env:
        out.append(env)
    # Caddyfile reverse_proxies git.fromchat.ru to host :3000
    for host in (
        "host.docker.internal:3000",
        "172.17.0.1:3000",
        "172.18.0.1:3000",
        "172.19.0.1:3000",
        "172.20.0.1:3000",
        "172.21.0.1:3000",
    ):
        out.append(f"http://{host}")
    out.append(GITEA_PUBLIC_BASE)
    seen: set[str] = set()
    unique: list[str] = []
    for base in out:
        if base and base not in seen:
            seen.add(base)
            unique.append(base)
    return unique


def _auth_headers(token: str | None, *, gitea: bool = False) -> dict[str, str]:
    token = _normalize_token(token)
    headers: dict[str, str] = {"User-Agent": "fromchat-git-remote"}
    if gitea:
        headers["Accept"] = "application/json"
        if token:
            headers["Authorization"] = f"token {token}"
        return headers
    headers["Accept"] = "application/vnd.github+json"
    headers["X-GitHub-Api-Version"] = "2022-11-28"
    if token:
        if token.startswith("github_pat_") or token.startswith("gho_"):
            headers["Authorization"] = f"Bearer {token}"
        else:
            headers["Authorization"] = f"token {token}"
    return headers


def _http_get_once(
    url: str,
    token: str | None,
    *,
    gitea: bool = False,
    insecure: bool = False,
) -> bytes:
    headers = _auth_headers(token, gitea=gitea)
    if gitea:
        host = urllib.parse.urlparse(url).hostname or ""
        if host and host not in (GITEA_PUBLIC_HOST, "localhost", "127.0.0.1"):
            headers["Host"] = GITEA_PUBLIC_HOST
    req = urllib.request.Request(url, headers=headers)
    context = ssl._create_unverified_context() if insecure else None
    with urllib.request.urlopen(req, timeout=45, context=context) as resp:
        return resp.read()


def _http_get(url: str, token: str | None, *, gitea: bool = False) -> bytes:
    if not gitea:
        return _http_get_once(url, token, gitea=False)
    try:
        return _http_get_once(url, token, gitea=True)
    except Exception:
        if url.startswith("https://"):
            try:
                return _http_get_once(url, token, gitea=True, insecure=True)
            except Exception:
                pass
        parsed = urllib.parse.urlparse(url)
        return _gitea_get(parsed.path + (f"?{parsed.query}" if parsed.query else ""), token)


def _gitea_get(path: str, token: str | None) -> bytes:
    """GET a Gitea path, probing host-gateway HTTP then public HTTPS."""
    global _gitea_working_base
    if not path.startswith("/"):
        path = "/" + path

    bases: list[str] = []
    if _gitea_working_base:
        bases.append(_gitea_working_base)
    for candidate in gitea_base_candidates():
        if candidate not in bases:
            bases.append(candidate)

    last_error: Exception | None = None
    for base in bases:
        url = f"{base.rstrip('/')}{path}"
        insecure_opts = (False, True) if base.startswith("https://") else (False,)
        for insecure in insecure_opts:
            try:
                data = _http_get_once(url, token, gitea=True, insecure=insecure)
                if _gitea_working_base != base:
                    _gitea_working_base = base
                    if base != GITEA_PUBLIC_BASE:
                        print(f"Using Gitea at {base}", file=sys.stderr)
                return data
            except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ssl.SSLError) as exc:
                last_error = exc
                continue
    raise RuntimeError(f"Gitea request failed for {path}") from last_error


def github_raw_url(owner: str, repo: str, ref: str, path: str) -> str:
    return f"https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path.lstrip('/')}"


def gitea_api_raw_path(gitea_owner: str, gitea_repo: str, ref: str, path: str) -> str:
    filepath = urllib.parse.quote(path.lstrip("/"), safe="/")
    ref_q = urllib.parse.quote(ref, safe="")
    return f"/api/v1/repos/{gitea_owner}/{gitea_repo}/raw/{filepath}?ref={ref_q}"


def gitea_web_raw_paths(gitea_owner: str, gitea_repo: str, ref: str, path: str) -> list[str]:
    p = path.lstrip("/")
    ref_q = urllib.parse.quote(ref, safe="")
    return [
        f"/{gitea_owner}/{gitea_repo}/raw/{ref_q}/{p}",
        f"/{gitea_owner}/{gitea_repo}/raw/tag/{ref_q}/{p}",
    ]


def fetch_gitea_raw(
    gitea_owner: str,
    gitea_repo: str,
    ref: str,
    path: str,
    token: str | None,
) -> bytes:
    paths = [gitea_api_raw_path(gitea_owner, gitea_repo, ref, path)]
    paths.extend(gitea_web_raw_paths(gitea_owner, gitea_repo, ref, path))

    last_error: Exception | None = None
    for rel in paths:
        try:
            return _gitea_get(rel, token)
        except (RuntimeError, urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
            last_error = exc
    raise RuntimeError(
        f"Failed to fetch {path} from git.fromchat.ru/{gitea_owner}/{gitea_repo}@{ref}"
    ) from last_error


def fetch_raw_file(repo_url: str, ref: str, path: str, token: str | None = None) -> bytes:
    token = _normalize_token(token)
    owner, repo = parse_slug(repo_url)

    if is_gitea_repo(repo_url):
        gitea_owner, gitea_repo = gitea_owner_repo(owner, repo)
        return fetch_gitea_raw(gitea_owner, gitea_repo, ref, path, token)

    try:
        return _http_get(github_raw_url(owner, repo, ref, path), token, gitea=False)
    except Exception as github_err:
        if not is_official_github_repo(repo_url):
            raise RuntimeError(
                f"Failed to fetch {path} from {repo_url}@{ref}"
            ) from github_err
        gitea_owner, gitea_repo = gitea_owner_repo(owner, repo)
        print(
            f"GitHub unavailable for {owner}/{repo}, "
            f"trying git.fromchat.ru/{gitea_owner}/{gitea_repo}…",
            file=sys.stderr,
        )
        return fetch_gitea_raw(gitea_owner, gitea_repo, ref, path, token)


def _tags_from_github(owner: str, repo: str, token: str | None) -> set[str]:
    data = json.loads(
        _http_get(
            f"https://api.github.com/repos/{owner}/{repo}/tags?per_page=100",
            token,
            gitea=False,
        ).decode()
    )
    return {item["name"] for item in data if SEMVER.fullmatch(item.get("name", ""))}


def _gitea_paginated_json(path: str, token: str | None) -> list[dict]:
    items: list[dict] = []
    page = 1
    while True:
        sep = "&" if "?" in path else "?"
        chunk = json.loads(_gitea_get(f"{path}{sep}page={page}&limit=50", token).decode())
        if not isinstance(chunk, list):
            break
        if not chunk:
            break
        items.extend(chunk)
        if len(chunk) < 50:
            break
        page += 1
    return items


def _tags_from_gitea(gitea_owner: str, gitea_repo: str, token: str | None) -> set[str]:
    out: set[str] = set()

    for item in _gitea_paginated_json(
        f"/api/v1/repos/{gitea_owner}/{gitea_repo}/tags",
        token,
    ):
        name = item.get("name", "")
        if SEMVER.fullmatch(name):
            out.add(name)

    if out:
        return out

    for item in _gitea_paginated_json(
        f"/api/v1/repos/{gitea_owner}/{gitea_repo}/releases",
        token,
    ):
        name = item.get("tag_name") or item.get("tag") or ""
        if SEMVER.fullmatch(name):
            out.add(name)

    return out


def fetch_semver_tags(repo_url: str, token: str | None = None) -> set[str]:
    token = _normalize_token(token)
    owner, repo = parse_slug(repo_url)

    if is_gitea_repo(repo_url):
        gitea_owner, gitea_repo = gitea_owner_repo(owner, repo)
        return _tags_from_gitea(gitea_owner, gitea_repo, token)

    try:
        return _tags_from_github(owner, repo, token)
    except Exception:
        if not is_official_github_repo(repo_url):
            raise
        gitea_owner, gitea_repo = gitea_owner_repo(owner, repo)
        print(
            f"GitHub tags unavailable for {owner}/{repo}, "
            f"trying git.fromchat.ru/{gitea_owner}/{gitea_repo}…",
            file=sys.stderr,
        )
        return _tags_from_gitea(gitea_owner, gitea_repo, token)


def semver_key(tag: str) -> tuple[int, ...]:
    m = SEMVER.match(tag)
    if not m:
        raise ValueError(tag)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3) or 0))


def resolve_common_tag(repo_urls: list[str], token: str | None = None) -> str:
    sets = [fetch_semver_tags(url, token) for url in repo_urls]
    common = set.intersection(*sets) if sets else set()
    if not common:
        raise RuntimeError("No common semver tag (vX.Y or vX.Y.Z) found across repos.")
    return max(common, key=semver_key)


def _image_tag_matches(needle: str, tag: str, candidates: list[str]) -> bool:
    n = needle.lstrip("v")
    t = tag.lstrip("v")
    for c in candidates:
        if not c:
            continue
        c = str(c)
        if c in (tag, needle, t, n, f"v{n}"):
            return True
    return False


def package_exists_github(github_owner: str, package: str, tag: str, token: str) -> bool:
    short = package.split("/", 1)[-1]
    url = (
        f"https://api.github.com/users/{github_owner}/packages/container/{short}/versions"
        "?per_page=20"
    )
    try:
        data = json.loads(_http_get(url, token, gitea=False).decode())
    except (urllib.error.HTTPError, urllib.error.URLError):
        return False
    if not isinstance(data, list):
        return False
    for version in data:
        meta = version.get("metadata", {}).get("container", {})
        tags = meta.get("tags") or []
        names = version.get("name") or version.get("version") or ""
        if _image_tag_matches(tag, tag, list(tags) + [names]):
            return True
    return False


def package_exists_gitea(gitea_owner: str, package: str, tag: str, token: str) -> bool:
    short = package.split("/", 1)[-1]
    path = f"/api/v1/packages/{gitea_owner}/container/{short}/versions"
    try:
        versions = _gitea_paginated_json(path, token)
    except (RuntimeError, urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError):
        return False

    for version in versions:
        names: list[str] = []
        for key in ("version", "name"):
            val = version.get(key)
            if isinstance(val, str):
                names.append(val)
        metadata = version.get("metadata") or {}
        if isinstance(metadata, dict):
            container = metadata.get("container") or {}
            if isinstance(container, dict):
                names.extend(str(t) for t in (container.get("tags") or []))
            tags_field = metadata.get("tags")
            if isinstance(tags_field, list):
                names.extend(str(t) for t in tags_field)
        if _image_tag_matches(tag, tag, names):
            return True
    return False


def package_version_exists(
    github_owner: str,
    package: str,
    tag: str,
    token: str,
    *,
    official: bool = True,
) -> bool:
    gitea_owner, _ = gitea_owner_repo(github_owner, package.split("/", 1)[-1])

    if github_owner == GITEA_ORG:
        return package_exists_gitea(gitea_owner, package, tag, token)

    if package_exists_github(github_owner, package, tag, token):
        return True
    if not official:
        return False
    if package_exists_gitea(gitea_owner, package, tag, token):
        print(
            f"GitHub packages unavailable for {package}, confirmed on git.fromchat.ru",
            file=sys.stderr,
        )
        return True
    return False


def resolve_git_token() -> str | None:
    return _normalize_token(
        os.environ.get("UPDATER_TOKEN")
        or os.environ.get("GIT_TOKEN")
        or os.environ.get("GITHUB_TOKEN")
    )


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(
            "usage: git_remote.py fetch-raw REPO REF PATH [TOKEN] | "
            "resolve-tag [TOKEN] REPO..."
        )

    cmd = sys.argv[1]
    args = sys.argv[2:]
    token: str | None = None
    if args and not args[0].startswith("http"):
        token = args[0]
        args = args[1:]
    token = token or resolve_git_token()

    if cmd == "fetch-raw":
        repo, ref, path = args[:3]
        sys.stdout.buffer.write(fetch_raw_file(repo, ref, path, token))
        return

    if cmd == "resolve-tag":
        print(resolve_common_tag(args, token))
        return

    sys.exit(f"Unknown command: {cmd}")


if __name__ == "__main__":
    main()
