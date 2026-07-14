#!/usr/bin/env python3
"""Patch updater compose.yml with runtime image settings (deploy / install)."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

import yaml


class IndentDumper(yaml.SafeDumper):
    def increase_indent(self, flow: bool = False, indentless: bool = False) -> None:
        return super().increase_indent(flow, False)


def write_runtime_compose(
    source: Path,
    output: Path,
    *,
    image: str,
    pull_policy: str = "never",
    strip_build: bool = True,
) -> None:
    with source.open(encoding="utf-8") as fh:
        doc: dict[str, Any] = yaml.safe_load(fh) or {}
    services = doc.setdefault("services", {})
    updater = dict(services.get("updater") or {})
    if strip_build:
        updater.pop("build", None)
    updater["image"] = image
    updater["pull_policy"] = pull_policy
    services["updater"] = updater
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as fh:
        yaml.dump(
            doc,
            fh,
            Dumper=IndentDumper,
            default_flow_style=False,
            sort_keys=False,
            allow_unicode=True,
            indent=2,
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Prepare updater compose for runtime deploy")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--pull-policy", default="never")
    parser.add_argument(
        "--keep-build",
        action="store_true",
        help="Keep build stanza (local dev only)",
    )
    args = parser.parse_args()
    write_runtime_compose(
        args.source,
        args.output,
        image=args.image,
        pull_policy=args.pull_policy,
        strip_build=not args.keep_build,
    )


if __name__ == "__main__":
    main()
