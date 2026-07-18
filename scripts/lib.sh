#!/usr/bin/env bash
# Shared helpers for FromChat deployment installer.

set -euo pipefail

NC=$'\033[0m'
GRAY=$'\033[38;5;245m'
BLUE=$'\033[38;5;81m'
PURPLE=$'\033[38;5;141m'
RED=$'\033[38;5;203m'
LIME=$'\033[38;5;154m'
ORANGE=$'\033[38;5;208m'
YELLOW=$'\033[38;5;226m'
CHECK=$'\033[38;5;154m'
CROSS=$'\033[38;5;203m'

info()    { printf '%b%s%b\n' "$BLUE" "$*" "$NC"; }
success() { printf '%b✓ %s%b\n' "$LIME" "$*" "$NC"; }
warn()    { printf '%b⚠ %s%b\n' "$YELLOW" "$*" "$NC"; }
error()   { printf '%b✗ %s%b\n' "$RED" "$*" "$NC" >&2; }
step()    { printf '\n%b▸ %s%b\n' "$PURPLE" "$*" "$NC"; }
prompt()  { printf '%b%s%b ' "$ORANGE" "$*" "$NC" >&2; }

die() {
  error "$1"
  exit "${2:-1}"
}

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    info "Elevating privileges with sudo…"
    exec sudo -E bash "$0" "$@"
  fi
}

is_debian_based() {
  [[ -f /etc/debian_version ]] || grep -qiE 'ubuntu|debian|mint|pop|elementary|raspbian' /etc/os-release 2>/dev/null
}

require_debian() {
  if ! is_debian_based; then
    die "This installer supports Debian-based Linux only (Debian, Ubuntu, etc.)."
  fi
  success "Debian-based OS detected"
}

docker_installed() {
  command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1
}

install_docker_if_needed() {
  if docker_installed; then
    success "Docker is already installed"
    return 0
  fi

  warn "Docker is not installed or not running."
  prompt "Install Docker using the official script? [Y/n]:"
  local answer
  IFS= read -r answer || true
  answer="${answer:-Y}"
  if [[ ! "${answer}" =~ ^[Yy] ]]; then
    die "Docker is required. Install it manually: https://docs.docker.com/engine/install/"
  fi

  step "Installing Docker…"
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker 2>/dev/null || true
  success "Docker installed"
}

ensure_python_yaml() {
  python3 - <<'PY' >/dev/null 2>&1 && return 0
import yaml  # noqa: F401
PY
  step "Installing python3-yaml…"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-yaml
}

ensure_compose_plugin() {
  if docker compose version >/dev/null 2>&1; then
    success "Docker Compose plugin available"
    return 0
  fi
  step "Installing docker-compose-plugin…"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-compose-plugin
}

repo_slug_from_url() {
  local url="$1"
  url="${url%.git}"
  for prefix in "github.com/" "git.fromchat.ru/" "https://" "http://"; do
    url="${url#*${prefix}}"
  done
  echo "${url%%/*}/${url##*/}"
}

fetch_raw_file() {
  local repo_url="$1"
  local tag="$2"
  local path="$3"
  local dest="$4"
  local script="${DEPLOYMENT_ROOT}/scripts/git_remote.py"
  local token="${GIT_TOKEN:-${GITHUB_TOKEN:-}}"
  if [[ -n "${token}" ]]; then
    python3 "${script}" fetch-raw "${repo_url}" "${tag}" "${path}" "${token}" > "${dest}"
  else
    python3 "${script}" fetch-raw "${repo_url}" "${tag}" "${path}" > "${dest}"
  fi
}

get_latest_semver_tag() {
  local repo_url="$1"
  local token="${GIT_TOKEN:-${GITHUB_TOKEN:-}}"
  if [[ -n "${token}" ]]; then
    python3 "${DEPLOYMENT_ROOT}/scripts/resolve-tag.py" "${token}" "${repo_url}"
  else
    python3 "${DEPLOYMENT_ROOT}/scripts/resolve-tag.py" "${repo_url}"
  fi
}

select_components() {
  local -a options=(backend frontend caddy updater chat_filter)
  local -a labels=(
    "Backend (API, DB, LiveKit)"
    "Web frontend"
    "Caddy reverse proxy (TLS)"
    "Auto-update service"
    "Chat content filter service"
  )
  # Updater opt-in (N); chat filter on by default (Y).
  local -a defaults=(Y Y Y N Y)
  SELECTED=()
  local i choice hint
  step "Select components"
  warn "Updater is in active development and probably won't even work. Enter skips it."
  for i in "${!options[@]}"; do
    if [[ "${defaults[$i]}" == "Y" ]]; then
      hint="Y/n"
    else
      hint="y/N"
    fi
    prompt "Include ${labels[$i]}? [${hint}]:"
    IFS= read -r choice || true
    choice="${choice#"${choice%%[![:space:]]*}"}"
    choice="${choice%"${choice##*[![:space:]]}"}"
    if [[ -z "${choice}" ]]; then
      [[ "${defaults[$i]}" == "Y" ]] && SELECTED+=("${options[$i]}")
      continue
    fi
    if [[ "${choice}" =~ ^[Yy] ]]; then
      SELECTED+=("${options[$i]}")
    fi
  done
  ((${#SELECTED[@]} > 0)) || die "Select at least one component."
}

component_selected() {
  local needle="$1"
  local item
  for item in "${SELECTED[@]:-}"; do
    [[ "${item}" == "${needle}" ]] && return 0
  done
  return 1
}

wait_for_compose_healthy() {
  local dir="$1"
  local timeout="${2:-600}"
  step "Waiting for services to become healthy (timeout ${timeout}s)…"
  (
    cd "${dir}"
    docker compose up -d --wait --timeout "${timeout}"
  )
}

print_success_banner() {
  local dir="$1"
  printf '\n%b╔══════════════════════════════════════════════════╗%b\n' "$LIME" "$NC"
  printf '%b║        FromChat server installed successfully!   ║%b\n' "$LIME" "$NC"
  printf '%b╚══════════════════════════════════════════════════╝%b\n\n' "$LIME" "$NC"
  info "Install directory: ${dir}"
  info "Version tag:       ${FROMCHAT_VERSION}"
  info "Manage stack:      cd ${dir} && docker compose ps"
  info "View logs:         cd ${dir} && docker compose logs -f"
  if component_selected caddy; then
    info "Caddy: baked into fromchat/caddy image (backend/src/caddy/Caddyfile)"
  fi
}

# Merge component compose files into ${install_dir}/compose.yml (image: fromchat/*:<tag>).
# Optional local compose paths (skip remote fetch when set):
#   LOCAL_BACKEND_COMPOSE, LOCAL_BACKEND_COMPOSE_PROD, LOCAL_FRONTEND_COMPOSE
# Requires: DEPLOYMENT_ROOT, BACKEND_REPO, WEB_REPO (for remote fetch).
run_generate_compose() {
  local install_dir="$1"
  local components_csv="$2"
  local tag="$3"
  local tmp
  tmp="$(mktemp -d)"

  step "Generating compose.yml for tag ${tag} (components: ${components_csv})…"

  local need_backend=false need_frontend=false need_caddy=false
  [[ ",${components_csv}," == *,backend,* ]] && need_backend=true
  [[ ",${components_csv}," == *,frontend,* ]] && need_frontend=true
  [[ ",${components_csv}," == *,caddy,* ]] && need_caddy=true

  if ${need_backend}; then
    if [[ -n "${LOCAL_BACKEND_COMPOSE:-}" && -f "${LOCAL_BACKEND_COMPOSE}" ]]; then
      cp -f "${LOCAL_BACKEND_COMPOSE}" "${tmp}/backend.compose.yml"
      success "Backend compose.yml (local)"
    else
      fetch_raw_file "${BACKEND_REPO}" "${tag}" "compose.yml" \
        "${tmp}/backend.compose.yml"
      success "Backend compose.yml"
    fi
    if ${need_caddy}; then
      if [[ -n "${LOCAL_BACKEND_COMPOSE_PROD:-}" && -f "${LOCAL_BACKEND_COMPOSE_PROD}" ]]; then
        cp -f "${LOCAL_BACKEND_COMPOSE_PROD}" "${tmp}/backend.compose.prod.yml"
        success "Backend compose.prod.yml (local)"
      else
        fetch_raw_file "${BACKEND_REPO}" "${tag}" "compose.prod.yml" \
          "${tmp}/backend.compose.prod.yml"
        success "Backend compose.prod.yml"
      fi
    fi
  fi
  if ${need_frontend}; then
    if [[ -n "${LOCAL_FRONTEND_COMPOSE:-}" && -f "${LOCAL_FRONTEND_COMPOSE}" ]]; then
      cp -f "${LOCAL_FRONTEND_COMPOSE}" "${tmp}/frontend.compose.yml"
      success "Frontend compose.yml (local)"
    else
      fetch_raw_file "${WEB_REPO}" "${tag}" "compose.yml" \
        "${tmp}/frontend.compose.yml"
      success "Frontend compose.yml"
    fi
  fi

  local stack_csv
  stack_csv="$(echo "${components_csv}" | tr ',' '\n' | grep -vE '^(updater|chat_filter)$' | paste -sd, - || true)"
  [[ -n "${stack_csv}" ]] || stack_csv="backend"

  local -a gen_args=(
    python3 "${DEPLOYMENT_ROOT}/scripts/generate-compose.py"
    --tag "${tag}"
    --components "${stack_csv}"
    --output "${install_dir}/compose.yml"
  )
  if ${need_backend}; then
    gen_args+=(--backend-compose "${tmp}/backend.compose.yml")
    if ${need_caddy}; then
      gen_args+=(--backend-compose-prod "${tmp}/backend.compose.prod.yml")
    fi
  fi
  if ${need_frontend}; then
    gen_args+=(--frontend-compose "${tmp}/frontend.compose.yml")
  fi
  if [[ ",${components_csv}," == *,updater,* ]]; then
    gen_args+=(--include-updater)
    if [[ -d "${DEPLOYMENT_ROOT}/../updater" ]]; then
      gen_args+=(--updater-root "${DEPLOYMENT_ROOT}/../updater")
    fi
  fi
  if [[ ",${components_csv}," == *,chat_filter,* ]]; then
    gen_args+=(--include-chat-filter)
  fi
  "${gen_args[@]}"

  echo "${tag}" > "${install_dir}/.fromchat-version"
  rm -rf "${tmp}"
}
