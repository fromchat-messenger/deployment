#!/usr/bin/env bash
# FromChat one-click server installer
# https://l.fromchat.ru/install
#
#   curl -fsSL https://l.fromchat.ru/install|sudo bash
set -euo pipefail

# curl|bash: take prompts from the real terminal
if [[ ! -t 0 && -c /dev/tty ]]; then
  exec </dev/tty
fi

NC=$'\033[0m'
BLUE=$'\033[38;5;81m'
PURPLE=$'\033[38;5;141m'
RED=$'\033[38;5;203m'
LIME=$'\033[38;5;154m'
ORANGE=$'\033[38;5;208m'
YELLOW=$'\033[38;5;226m'

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

BACKEND_REPO="${FROMCHAT_BACKEND_REPO:-https://github.com/fromchat-messenger/backend.git}"

COMPOSE_URLS=(
  "https://raw.githubusercontent.com/fromchat-messenger/deployment/main/compose.yml"
  "https://git.fromchat.ru/FromChat/deployment/raw/branch/main/compose.yml"
)

usage() {
  cat <<EOF
FromChat server installer

  curl -fsSL https://l.fromchat.ru/install|sudo bash

What it does:
  1. Installs Docker (if needed)
  2. Downloads the published compose.yml
  3. Runs the backend .env generator into ~/fromchat-server/.env
  4. Prints next steps (configure Caddyfile, docker compose up -d)

Containers use restart: always — no systemd unit is installed.

Options:
  --output-dir PATH   Install directory (default: ~/fromchat-server)
  -h, --help          Show this help
EOF
}

OUTPUT_DIR=""

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --output-dir)
        OUTPUT_DIR="${2:-}"
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

require_root() {
  if [[ "${EUID}" -ne 0 ]]; then
    die "Run as root:
  curl -fsSL https://l.fromchat.ru/install|sudo bash"
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

install_docker() {
  if docker_installed; then
    success "Docker is already installed"
    return 0
  fi
  step "Installing Docker…"
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker 2>/dev/null || true
  docker_installed || die "Docker install finished but docker is not usable."
  success "Docker installed"
}

ensure_compose_plugin() {
  if docker compose version >/dev/null 2>&1; then
    success "Docker Compose plugin available"
    return 0
  fi
  step "Installing docker-compose-plugin…"
  apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-compose-plugin
  docker compose version >/dev/null 2>&1 || die "docker compose plugin is required."
  success "Docker Compose plugin installed"
}

ensure_python() {
  command -v python3 >/dev/null 2>&1 || {
    step "Installing python3…"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 python3-venv python3-pip
  }
  command -v git >/dev/null 2>&1 || {
    step "Installing git…"
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git
  }
  command -v openssl >/dev/null 2>&1 || {
    apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssl
  }
}

default_install_dir() {
  local user home
  user="${SUDO_USER:-${USER}}"
  home="$(getent passwd "${user}" 2>/dev/null | cut -d: -f6 || true)"
  if [[ -z "${home}" ]]; then
    home="${HOME}"
  fi
  echo "${home}/fromchat-server"
}

download_compose() {
  local dest="$1"
  local url
  step "Downloading compose.yml…"
  for url in "${COMPOSE_URLS[@]}"; do
    if curl -fsSL --connect-timeout 20 --max-time 60 "${url}" -o "${dest}"; then
      if [[ -s "${dest}" ]] && grep -qE '^services:' "${dest}"; then
        success "Downloaded compose.yml"
        info "Source: ${url}"
        return 0
      fi
    fi
  done
  die "Could not download compose.yml from GitHub or Gitea."
}

run_env_generator() {
  local install_dir="$1"
  local real_user="${SUDO_USER:-${USER}}"
  local tmp backend_dir

  step "Preparing .env generator (backend repo)…"
  ensure_python
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '${tmp}'" RETURN

  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
    chown "${real_user}:${real_user}" "${tmp}"
  fi

  if ! sudo -u "${real_user}" git clone --depth 1 "${BACKEND_REPO}" "${tmp}/backend" >/dev/null 2>&1; then
    if ! sudo -u "${real_user}" git clone --depth 1 \
      "https://git.fromchat.ru/FromChat/backend.git" "${tmp}/backend" >/dev/null 2>&1; then
      die "Could not clone backend repo for the .env generator."
    fi
  fi
  backend_dir="${tmp}/backend"

  sudo -u "${real_user}" python3 -m venv "${backend_dir}/.venv"
  sudo -u "${real_user}" "${backend_dir}/.venv/bin/pip" -q install --upgrade pip
  sudo -u "${real_user}" "${backend_dir}/.venv/bin/pip" -q install cryptography

  step "Generating .env (follow prompts)…"
  info "Default output: ${install_dir}/.env (press Enter when asked for the path)"
  info "Compliance keypair → ${install_dir}/compliance_keypair.txt"

  sudo -u "${real_user}" \
    env FROMCHAT_ENV_OUT="${install_dir}/.env" \
        FROMCHAT_COMPLIANCE_OUT="${install_dir}/compliance_keypair.txt" \
        bash "${backend_dir}/scripts/generate:env.sh"

  [[ -f "${install_dir}/.env" ]] || die ".env was not created."
  success ".env written to ${install_dir}/.env"
}

print_success() {
  local dir="$1"
  printf '\n%b╔══════════════════════════════════════════════════╗%b\n' "$LIME" "$NC"
  printf '%b║        FromChat files are ready                   ║%b\n' "$LIME" "$NC"
  printf '%b╚══════════════════════════════════════════════════╝%b\n\n' "$LIME" "$NC"

  info "Install directory: ${dir}"
  info ""
  info "Next steps:"
  info "  1. Configure Caddy (domains / reverse proxy) by editing the Caddyfile in the"
  info "     backend repo (backend/src/caddy/Caddyfile), then rebuild/republish"
  info "     fromchat/caddy — or replace domains to match your server."
  info "  2. Start the stack:"
  info "       cd ${dir}"
  info "       docker compose --env-file .env up -d"
  info ""
  info "Containers use restart: always (no systemd service)."
  info "Data lives under ${dir}/data/prod/ (Caddy ACME certs in data/prod/caddy/)."
  info "Keep compliance_keypair.txt offline — only COMPLIANCE_PUBLIC_KEY stays in .env."
}

main() {
  parse_args "$@"

  require_root
  require_debian
  install_docker
  ensure_compose_plugin

  local real_user install_dir
  real_user="${SUDO_USER:-${USER}}"
  install_dir="${OUTPUT_DIR:-$(default_install_dir)}"

  step "Install directory: ${install_dir}"
  mkdir -p "${install_dir}/data/prod"
  chown -R "${real_user}:${real_user}" "${install_dir}" 2>/dev/null || true

  download_compose "${install_dir}/compose.yml"
  chown "${real_user}:${real_user}" "${install_dir}/compose.yml" 2>/dev/null || true

  touch "${install_dir}/firebase-cert.json" 2>/dev/null || true
  chown "${real_user}:${real_user}" "${install_dir}/firebase-cert.json" 2>/dev/null || true

  run_env_generator "${install_dir}"
  chown -R "${real_user}:${real_user}" "${install_dir}" 2>/dev/null || true

  print_success "${install_dir}"
}

main "$@"
