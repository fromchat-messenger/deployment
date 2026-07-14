"""SSH key agent, host trust, pubkey install, and optional sudo password."""

from __future__ import annotations

import getpass
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import deploy.ui as ui

DEFAULT_KEY_FILE = Path.home() / ".ssh" / "id_rsa"
KEYGEN_DISPLAY = (
    'ssh-keygen -t rsa -b 4096 -f ~/.ssh/id_rsa -N "" -C "fromchat-deploy"'
)


@dataclass
class SshCredentials:
    server: str
    sudo_password: str


def ssh_common_options() -> list[str]:
    return [
        "-o",
        "StrictHostKeyChecking=accept-new",
        "-o",
        "ConnectTimeout=10",
    ]


def ssh_argv(server: str, remote_command: str) -> list[str]:
    return ["ssh", *ssh_common_options(), server, remote_command]


def scp_argv(local: str, remote: str) -> list[str]:
    return ["scp", *ssh_common_options(), local, remote]


class SshAuth:
    def __init__(self, server: str) -> None:
        self._server = server

    def authenticate(self) -> SshCredentials:
        ui.step("Authentication")
        self._ensure_agent()
        key_file = self._resolve_key_file()
        self._ensure_key_file(key_file)
        self._ensure_key_in_agent(key_file)
        self._trust_host_key()
        sudo_password = self._read_sudo_password()
        self._verify_key_auth(key_file, sudo_password)
        sudo_password = self._verify_sudo_password(sudo_password)
        return SshCredentials(server=self._server, sudo_password=sudo_password)

    def _read_sudo_password(self) -> str:
        pw = getpass.getpass("  • Sudo password: ")
        if not pw:
            ui.warning("No password provided - assuming passwordless sudo")
        return pw

    def _resolve_key_file(self) -> Path:
        env_key = os.environ.get("FROMCHAT_SSH_KEY", "").strip()
        if env_key:
            return Path(env_key).expanduser()
        ssh_dir = Path.home() / ".ssh"
        for name in ("id_ed25519", "id_rsa"):
            candidate = ssh_dir / name
            if candidate.is_file():
                return candidate
        return DEFAULT_KEY_FILE

    def _prompt_yes_no(self, message: str, *, default: bool = True) -> bool:
        if not sys.stdin.isatty():
            return default
        hint = "Y/n" if default else "y/N"
        answer = input(f"  {message} [{hint}]: ").strip()
        if not answer:
            return default
        return answer.lower().startswith("y")

    def _ensure_agent(self) -> None:
        if os.environ.get("SSH_AUTH_SOCK"):
            return
        subprocess.run(["ssh-agent", "-s"], capture_output=True, check=False)

    def _ensure_key_file(self, key_file: Path) -> None:
        if key_file.is_file():
            return
        ui.warning(f"SSH key not found at {key_file}")
        print(f"  Command: {KEYGEN_DISPLAY}")
        if not self._prompt_yes_no("Create SSH key now?", default=True):
            ui.error("SSH key is required for deploy.")
            raise SystemExit(1)
        key_file.parent.mkdir(mode=0o700, exist_ok=True)
        ui.substep("Creating SSH key...")
        result = subprocess.run(
            [
                "ssh-keygen",
                "-t",
                "rsa",
                "-b",
                "4096",
                "-f",
                str(key_file),
                "-N",
                "",
                "-C",
                "fromchat-deploy",
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            ui.error("Failed to create SSH key")
            if result.stderr:
                print(result.stderr, file=sys.stderr)
            raise SystemExit(1)
        ui.success(f"Created {key_file}")

    def _ensure_key_in_agent(self, key_file: Path) -> None:
        loaded = False
        r = subprocess.run(["ssh-add", "-l"], capture_output=True, text=True)
        if r.returncode == 0:
            fp_r = subprocess.run(
                ["ssh-keygen", "-lf", str(key_file)],
                capture_output=True,
                text=True,
            )
            if fp_r.returncode == 0:
                parts = fp_r.stdout.strip().split()
                fingerprint = parts[1] if len(parts) > 1 else ""
                if fingerprint and fingerprint in r.stdout:
                    loaded = True
        if not loaded:
            ui.substep("Adding SSH key to agent...")
            add = subprocess.run(["ssh-add", str(key_file)], capture_output=True, text=True)
            if add.returncode != 0:
                ui.error("Failed to add SSH key to agent.")
                if add.stderr:
                    print(add.stderr, file=sys.stderr)
                raise SystemExit(1)

    def _server_host(self) -> str:
        host = self._server.split("@", 1)[-1]
        if host.startswith("[") and "]" in host:
            return host[1 : host.index("]")]
        return host.split(":", 1)[0]

    def _trust_host_key(self) -> None:
        host = self._server_host()
        if not host:
            return
        known_hosts = Path.home() / ".ssh" / "known_hosts"
        known_hosts.parent.mkdir(mode=0o700, exist_ok=True)
        if known_hosts.is_file():
            try:
                with known_hosts.open(encoding="utf-8") as fh:
                    if host in fh.read():
                        return
            except OSError:
                pass
        ui.substep(f"Trusting host key for {host}...")
        try:
            scan = subprocess.run(
                ["ssh-keyscan", "-H", host],
                capture_output=True,
                text=True,
                timeout=30,
            )
        except (subprocess.TimeoutExpired, OSError) as exc:
            ui.warning(f"Could not scan host key for {host}: {exc}")
            return
        if scan.returncode != 0 or not scan.stdout.strip():
            ui.warning(f"ssh-keyscan returned no keys for {host}")
            return
        with known_hosts.open("a", encoding="utf-8") as fh:
            fh.write(scan.stdout)
            if not scan.stdout.endswith("\n"):
                fh.write("\n")
        ui.success(f"Host key for {host} added to known_hosts")

    def _test_key_auth(self) -> bool:
        return (
            subprocess.run(
                [
                    "ssh",
                    "-o",
                    "BatchMode=yes",
                    *ssh_common_options(),
                    self._server,
                    "echo SSH key works",
                ],
                capture_output=True,
            ).returncode
            == 0
        )

    def _install_pubkey(self, pub: Path, password: str) -> bool:
        ui.substep(f"Installing public key on {self._server}...")
        base_cmd = [
            "ssh-copy-id",
            "-i",
            str(pub),
            *ssh_common_options(),
            self._server,
        ]
        if password and shutil.which("sshpass"):
            env = os.environ.copy()
            env["SSHPASS"] = password
            result = subprocess.run(["sshpass", "-e", *base_cmd], env=env)
            return result.returncode == 0
        if password:
            ui.substep("sshpass not found — enter the same password when ssh-copy-id prompts")
        return subprocess.run(base_cmd).returncode == 0

    def _verify_key_auth(self, key_file: Path, sudo_password: str) -> None:
        pub = key_file.with_suffix(key_file.suffix + ".pub")
        if not pub.is_file():
            ui.error(f"Missing public key: {pub}")
            raise SystemExit(1)

        if self._test_key_auth():
            ui.success("SSH key authentication works")
            return

        ui.warning(f"SSH key authentication failed for {self._server}")
        if self._prompt_yes_no(
            f"Install your public key on {self._server} with ssh-copy-id?",
            default=True,
        ):
            if self._install_pubkey(pub, sudo_password) and self._test_key_auth():
                ui.success("SSH key installed and verified")
                return

        ui.error("SSH key authentication still failing")
        sys.stderr.write(
            f'  Try manually: ssh-copy-id -i "{pub}" "{self._server}"\n'
        )
        if pub.is_file():
            sys.stderr.write(f"  Public key: {pub.read_text(encoding='utf-8').strip()}\n")
        raise SystemExit(1)

    def _verify_sudo_password(self, password: str) -> str:
        if not password:
            return ""
        while True:
            chk = subprocess.run(
                ssh_argv(self._server, "sudo -S -v"),
                input=(password + "\n").encode(),
                capture_output=True,
            )
            if chk.returncode == 0:
                return password
            ui.error("Invalid sudo password, please try again")
            password = getpass.getpass("  • Sudo password: ")
            if not password:
                ui.warning("No password provided - assuming passwordless sudo")
                return ""
