#!/usr/bin/env bash
# Classic FromChat deploy: build local images, generate production compose, push to server.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${SCRIPT_DIR}"
SCRIPTS="${DEPLOYMENT_ROOT}/scripts"

# Prefer backend venv (has rich + dotenv), then deployment .venv, then python3.
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
exec "${PYTHON}" -m deploy.main "$@"
