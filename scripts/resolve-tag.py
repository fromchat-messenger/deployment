#!/usr/bin/env python3
"""Pick the latest semver tag present in all given repos (GitHub, Gitea fallback)."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from git_remote import resolve_common_tag  # noqa: E402


def main() -> None:
    args = sys.argv[1:]
    token: str | None = None
    if args and not args[0].startswith("http"):
        token = args[0]
        args = args[1:]
    token = token or resolve_git_token()
    if not args:
        sys.exit("usage: resolve-tag.py [TOKEN] REPO_URL ...")
    print(resolve_common_tag(args, token))


if __name__ == "__main__":
    main()
