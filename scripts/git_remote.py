#!/usr/bin/env python3
"""
GitHub + Gitea (git.fromchat.ru) helpers for FromChat deployment/updater.

Official GitHub: github.com/fromchat-messenger/{backend,web,app}
Gitea:           git.fromchat.ru/FromChat/{same repo name}

Official GitHub URLs fall back to Gitea when GitHub fails.
URLs already on git.fromchat.ru use Gitea APIs directly.
"""
from __future__ import annotations

import json
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
GITEA_BASE = "https://git.fromchat.ru"

OFFICIAL_GITHUB_REPOS = frozenset(
    {
        f"https://github.com/{GITHUB_ORG}/backend.git",
        f"https://github.com/{GITHUB_ORG}/web.git",
        f"https://github.com/{GITHUB_ORG}/app.git",
    }
)


class GitRemoteUnavailableError(OSError):
    """GitHub and/or Gitea could not provide release metadata."""

    def __init__(
        self,
        repo_label: str,
        *,
        github_error: Exception | None = None,
        gitea_error: Exception | None = None,
    ) -> None:
        self.repo_label = repo_label
        self.github_error = github_error
        self.gitea_error = gitea_error
        details: list[str] = []
        if github_error is not None:
            details.append(f"GitHub: {_brief_http_error(github_error)}")
        if gitea_error is not None:
            details.append(f"Gitea: {_brief_http_error(gitea_error)}")
        detail_text = "; ".join(details) if details else "service unavailable"
        super().__init__(f"Release metadata unavailable for {repo_label} ({detail_text})")


def _brief_http_error(exc: Exception) -> str:
    if isinstance(exc, urllib.error.HTTPError):
        return f"HTTP {exc.code}"
    if isinstance(exc, urllib.error.URLError):
        reason = exc.reason
        if isinstance(reason, ssl.SSLError):
            return f"SSL {reason.reason}"
        if reason is not None:
            return str(reason)
        return "connection failed"
    return type(exc).__name__


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


def _http_get(url: str, token: str | None, *, gitea: bool = False) -> bytes:
    req = urllib.request.Request(
        url,
        headers=_auth_headers(token, gitea=gitea),
    )
    with urllib.request.urlopen(req, timeout=45) as resp:
        return resp.read()


def github_raw_url(owner: str, repo: str, ref: str, path: str) -> str:
    return f"https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path.lstrip('/')}"


def gitea_api_raw_url(gitea_owner: str, gitea_repo: str, ref: str, path: str) -> str:
    """Gitea API raw file (supports branch/tag/commit via ?ref=)."""
    filepath = urllib.parse.quote(path.lstrip("/"), safe="/")
    ref_q = urllib.parse.quote(ref, safe="")
    return (
        f"{GITEA_BASE}/api/v1/repos/{gitea_owner}/{gitea_repo}/raw/{filepath}?ref={ref_q}"
    )


def gitea_web_raw_urls(gitea_owner: str, gitea_repo: str, ref: str, path: str) -> list[str]:
    """Legacy/browser raw paths (tried if API raw fails)."""
    p = path.lstrip("/")
    ref_q = urllib.parse.quote(ref, safe="")
    return [
        f"{GITEA_BASE}/{gitea_owner}/{gitea_repo}/raw/{ref_q}/{p}",
        f"{GITEA_BASE}/{gitea_owner}/{gitea_repo}/raw/tag/{ref_q}/{p}",
    ]


def fetch_gitea_raw(
    gitea_owner: str,
    gitea_repo: str,
    ref: str,
    path: str,
    token: str | None,
) -> bytes:
    urls = [gitea_api_raw_url(gitea_owner, gitea_repo, ref, path)]
    urls.extend(gitea_web_raw_urls(gitea_owner, gitea_repo, ref, path))

    last_error: Exception | None = None
    for url in urls:
        try:
            return _http_get(url, token, gitea=True)
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
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
        url = f"{GITEA_BASE}{path}{sep}page={page}&limit=50"
        chunk = json.loads(_http_get(url, token, gitea=True).decode())
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

    # Fallback: release tags
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
        try:
            return _tags_from_gitea(gitea_owner, gitea_repo, token)
        except Exception as err:
            raise GitRemoteUnavailableError(
                f"git.fromchat.ru/{gitea_owner}/{gitea_repo}",
                gitea_error=err,
            ) from err

    try:
        return _tags_from_github(owner, repo, token)
    except Exception as github_err:
        if not is_official_github_repo(repo_url):
            raise
        gitea_owner, gitea_repo = gitea_owner_repo(owner, repo)
        print(
            f"GitHub tags unavailable for {owner}/{repo}, "
            f"trying git.fromchat.ru/{gitea_owner}/{gitea_repo}…",
            file=sys.stderr,
        )
        try:
            return _tags_from_gitea(gitea_owner, gitea_repo, token)
        except Exception as gitea_err:
            raise GitRemoteUnavailableError(
                f"{owner}/{repo}",
                github_error=github_err,
                gitea_error=gitea_err,
            ) from gitea_err


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
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError):
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
    import os

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
