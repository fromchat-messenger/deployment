#!/usr/bin/env bash
# FromChat one-click server installer
# https://github.com/fromchat-messenger/app
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOYMENT_ROOT="${SCRIPT_DIR}"
# shellcheck source=scripts/lib.sh
source "${DEPLOYMENT_ROOT}/scripts/lib.sh"

DEFAULT_BACKEND_REPO="${FROMCHAT_BACKEND_REPO:-https://github.com/fromchat-messenger/backend.git}"
DEFAULT_WEB_REPO="${FROMCHAT_WEB_REPO:-https://github.com/fromchat-messenger/web.git}"
DEFAULT_APP_REPO="${FROMCHAT_APP_REPO:-https://github.com/fromchat-messenger/app.git}"

INSTALL_DIR=""
FROMCHAT_VERSION=""
BACKEND_REPO="${DEFAULT_BACKEND_REPO}"
WEB_REPO="${DEFAULT_WEB_REPO}"
GENERATE_ONLY=false
GENERATE_COMPONENTS=""
GENERATE_TAG=""
GENERATE_OUTPUT=""

usage() {
  cat <<EOF
FromChat server installer (experimental)

  curl -fsSL https://raw.githubusercontent.com/fromchat-messenger/app/main/deployment/install.sh | sudo bash

  Note: the updater is in active development and probably will not work;
  Enter skips it by default.

Options:
  --generate-config SERVICES   Merge compose files only (comma-separated:
                               backend,frontend,caddy). Example:
                               --generate-config backend,frontend,caddy
  --tag TAG                    Image / git tag (required with --generate-config)
  --output-dir PATH            Output directory (default: ./fromchat-server)
  --backend-repo URL           Backend git repository
  --web-repo URL               Web frontend git repository
  -h, --help                   Show this help
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --generate-config)
        GENERATE_ONLY=true
        GENERATE_COMPONENTS="${2:-}"
        shift 2
        ;;
      --tag)
        GENERATE_TAG="${2:-}"
        shift 2
        ;;
      --output-dir)
        GENERATE_OUTPUT="${2:-}"
        shift 2
        ;;
      --backend-repo)
        BACKEND_REPO="${2:-}"
        shift 2
        ;;
      --web-repo)
        WEB_REPO="${2:-}"
        shift 2
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "Unknown argument: $1"
        ;;
    esac
  done
}

real_install_dir() {
  local user home
  user="${SUDO_USER:-${USER}}"
  home="$(getent passwd "${user}" 2>/dev/null | cut -d: -f6 || true)"
  if [[ -z "${home}" ]]; then
    home="${HOME}"
  fi
  echo "${home}/fromchat-server"
}

configure_caddyfile() {
  local install_dir="$1"
  warn "Caddyfile is built into the fromchat/caddy image (backend/src/caddy/Caddyfile)."
  warn "Edit it in the backend repo and redeploy to change reverse-proxy config."
}

write_updater_env() {
  local install_dir="$1"
  local tag="$2"
  local updater_dir="${install_dir}/updater"
  mkdir -p "${updater_dir}"

  local token="${GITHUB_TOKEN:-${GIT_TOKEN:-}}"
  if [[ -z "${token}" ]]; then
    step "Git token for the auto-updater (GitHub or Gitea: read packages + repo metadata)."
    info "GitHub token:"
    printf '%b%s%b\n' "$BLUE" \
      "  https://github.com/settings/tokens/new?description=FromChat%20Updater&scopes=read:packages,repo" \
      "$NC"
    info "Gitea token: Settings → Applications → Generate New Token (read:repository, read:package)"
    prompt "Paste token (input hidden):"
    IFS= read -rs token || true
    printf '\n' >&2
    [[ -n "${token}" ]] || die "GitHub token is required when updater is selected."
    export GITHUB_TOKEN="${token}"
    export GIT_TOKEN="${token}"
  fi

  local components_csv
  components_csv="$(IFS=,; echo "${SELECTED[*]}")"

  cat > "${updater_dir}/.env" <<EOF
BACKEND_REPO=${BACKEND_REPO}
WEB_REPO=${WEB_REPO}
DEPLOYMENT_REPO=${DEFAULT_APP_REPO}
COMPOSE_PROJECT_DIR=${install_dir}
FROMCHAT_COMPONENTS=${components_csv}
CHECK_INTERVAL_SECONDS=60
EOF
  if [[ -n "${token}" ]]; then
    {
      echo "UPDATER_TOKEN=${token}"
    } >> "${install_dir}/.env"
  fi
  success "Updater env written"
}

generate_config_mode() {
  [[ -n "${GENERATE_COMPONENTS}" ]] || die "--generate-config requires a service list"
  [[ -n "${GENERATE_TAG}" ]] || die "--tag is required with --generate-config"

  local out="${GENERATE_OUTPUT:-$(real_install_dir)}"
  mkdir -p "${out}"
  ensure_python_yaml
  run_generate_compose "${out}" "${GENERATE_COMPONENTS}" "${GENERATE_TAG}"
  success "Generated ${out}/compose.yml"
}

full_install() {
  require_root "$@"
  require_debian
  install_docker_if_needed
  ensure_python_yaml
  ensure_compose_plugin

  warn "This installer is experimental."
  warn "Updater is in active development and probably won't even work (skipped unless you opt in)."

  local real_user real_home
  real_user="${SUDO_USER:-${USER}}"
  real_home="$(getent passwd "${real_user}" | cut -d: -f6)"
  INSTALL_DIR="${real_home}/fromchat-server"

  step "Install directory: ${INSTALL_DIR}"
  mkdir -p "${INSTALL_DIR}"
  chown "${real_user}:${real_user}" "${INSTALL_DIR}" 2>/dev/null || true

  SELECTED=()
  select_components

  prompt "Use custom git repositories? [y/N]:"
  local custom
  IFS= read -r custom || true
  if [[ "${custom:-}" =~ ^[Yy] ]]; then
    prompt "Backend repository URL [${DEFAULT_BACKEND_REPO}]:"
    IFS= read -r line || true
    [[ -n "${line}" ]] && BACKEND_REPO="${line}"
    prompt "Web repository URL [${DEFAULT_WEB_REPO}]:"
    IFS= read -r line || true
    [[ -n "${line}" ]] && WEB_REPO="${line}"
  fi

  step "Resolving latest common semver tag…"
  local repos_for_tag=()
  component_selected backend && repos_for_tag+=("${BACKEND_REPO}")
  component_selected frontend && repos_for_tag+=("${WEB_REPO}")
  if ((${#repos_for_tag[@]} == 0)); then
    repos_for_tag+=("${BACKEND_REPO}" "${WEB_REPO}")
  fi

  step "GitHub / Gitea token for private repos (leave empty if public):"
  prompt "Paste token (input hidden):"
  local gh_token=""
  IFS= read -rs gh_token || true
  printf '\n' >&2
  export GITHUB_TOKEN="${gh_token}"
  export GIT_TOKEN="${gh_token}"

  if [[ -n "${GITHUB_TOKEN}" ]]; then
    FROMCHAT_VERSION="$(python3 "${DEPLOYMENT_ROOT}/scripts/resolve-tag.py" "${GITHUB_TOKEN}" "${repos_for_tag[@]}")"
  else
    FROMCHAT_VERSION="$(python3 "${DEPLOYMENT_ROOT}/scripts/resolve-tag.py" "${repos_for_tag[@]}")"
  fi
  [[ -n "${FROMCHAT_VERSION}" ]] || die "Could not resolve a semver tag."
  success "Using tag ${FROMCHAT_VERSION}"

  local components_csv
  components_csv="$(IFS=,; echo "${SELECTED[*]}")"
  run_generate_compose "${INSTALL_DIR}" "${components_csv}" "${FROMCHAT_VERSION}"

  if component_selected caddy; then
    configure_caddyfile "${INSTALL_DIR}"
  fi

  if [[ ! -f "${INSTALL_DIR}/.env" ]]; then
    warn "No .env in ${INSTALL_DIR}. Create deployment/.env.prod locally and redeploy, or copy it to ${INSTALL_DIR}/.env before production use."
    touch "${INSTALL_DIR}/.env"
    chown "${real_user}:${real_user}" "${INSTALL_DIR}/.env" 2>/dev/null || true
  fi

  if component_selected updater; then
    export GITHUB_TOKEN="${gh_token:-${GITHUB_TOKEN:-}}"
    export GIT_TOKEN="${gh_token:-${GIT_TOKEN:-${GITHUB_TOKEN:-}}}"
    write_updater_env "${INSTALL_DIR}" "${FROMCHAT_VERSION}"
  fi

  step "Starting FromChat stack…"
  (
    cd "${INSTALL_DIR}"
    docker compose --env-file .env up -d --remove-orphans
  )

  chown -R "${real_user}:${real_user}" "${INSTALL_DIR}" 2>/dev/null || true
  print_success_banner "${INSTALL_DIR}"
}

main() {
  parse_args "$@"
  if ${GENERATE_ONLY}; then
    generate_config_mode
  else
    full_install "$@"
  fi
}

main "$@"
