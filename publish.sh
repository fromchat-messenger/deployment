#!/usr/bin/env bash
# Build FromChat images for linux/amd64 + linux/arm64 and push to GitHub Packages
# + Gitea (Docker Hub optional). Tags match each component's git tag on HEAD and
# also publish `:latest`. Native-platform images stay tagged locally as
# fromchat/<service>:<version> and fromchat/<service>:latest.
#
#   ./publish.sh                  # build + push (github + gitea always)
#   ./publish.sh --no-build       # retag/push existing local fromchat/* images only
#   ./publish.sh --no-push        # build only (still loads local tags)
#   ./publish.sh --push all       # also include Docker Hub
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${SCRIPT_DIR}"
SCRIPTS="${DEPLOYMENT_ROOT}/scripts"

PYTHON=""
for candidate in \
  "${DEPLOYMENT_ROOT}/../backend/.venv/bin/python3" \
  "${DEPLOYMENT_ROOT}/.venv/bin/python3" \
  "$(command -v python3 || true)"
do
  if [[ -n "${candidate}" && -x "${candidate}" ]]; then
    PYTHON="${candidate}"
    break
  fi
done
[[ -n "${PYTHON}" ]] || { echo "python3 not found" >&2; exit 1; }

export PYTHONPATH="${SCRIPTS}${PYTHONPATH:+:${PYTHONPATH}}"
exec "${PYTHON}" -m deploy.publish "$@"
