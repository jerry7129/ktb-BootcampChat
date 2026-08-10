#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  sudo [NODE_BIN=/absolute/path/to/node] [JAVA_BIN=/absolute/path/to/java] \
    ./provision-ec2-systemd.sh <frontend|backend|all> <deploy-user>

Examples:
  sudo NODE_BIN="$(command -v node)" ./provision-ec2-systemd.sh frontend ubuntu
  sudo JAVA_BIN="$(command -v java)" ./provision-ec2-systemd.sh backend ubuntu
EOF
}

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  usage
  exit 1
fi

role="${1:-}"
deploy_user="${2:-}"

case "$role" in
  frontend|backend|all) ;;
  *)
    usage
    exit 1
    ;;
esac

if [[ -z "$deploy_user" ]] || ! id "$deploy_user" >/dev/null 2>&1; then
  echo "Deploy user does not exist: $deploy_user" >&2
  exit 1
fi

deploy_home="$(getent passwd "$deploy_user" | cut -d: -f6)"
if [[ -z "$deploy_home" || ! -d "$deploy_home" ]]; then
  echo "Could not determine the home directory for $deploy_user" >&2
  exit 1
fi

resolve_user_command() {
  local command_name="$1"
  local configured_path="$2"
  local resolved=""

  if [[ -n "$configured_path" ]]; then
    resolved="$configured_path"
  else
    resolved="$(runuser -u "$deploy_user" -- bash -lc "command -v $command_name" 2>/dev/null || true)"
  fi

  if [[ -z "$resolved" || ! -x "$resolved" ]]; then
    echo "Could not find executable '$command_name' for $deploy_user." >&2
    echo "Install it first or pass its absolute path with ${command_name^^}_BIN." >&2
    exit 1
  fi

  readlink -f "$resolved"
}

install_frontend_unit() {
  local node_bin node_major frontend_root unit_file
  node_bin="$(resolve_user_command node "${NODE_BIN:-}")"
  node_major="$($node_bin --version | sed -E 's/^v([0-9]+).*/\1/')"
  if [[ ! "$node_major" =~ ^[0-9]+$ || "$node_major" -lt 20 ]]; then
    echo "Node.js 20 or newer is required; found: $($node_bin --version)" >&2
    exit 1
  fi

  frontend_root="$deploy_home/ktb-chat-frontend"
  install -d -o "$deploy_user" -g "$deploy_user" \
    "$frontend_root" "$frontend_root/releases"

  unit_file="$(mktemp)"
  cat >"$unit_file" <<EOF
[Unit]
Description=KTB Chat frontend
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=$deploy_user
Group=$deploy_user
WorkingDirectory=$frontend_root/current
Environment=NODE_ENV=production
Environment=PORT=3000
ExecStart=$node_bin $frontend_root/current/apps/frontend/server.js
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
KillSignal=SIGTERM
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
  install -m 644 "$unit_file" /etc/systemd/system/ktb-frontend.service
  rm -f "$unit_file"
  systemctl enable ktb-frontend.service
  echo "Installed ktb-frontend.service (Node: $node_bin)"
}

install_backend_unit() {
  local java_bin java_version backend_root unit_file
  java_bin="$(resolve_user_command java "${JAVA_BIN:-}")"
  java_version="$($java_bin -version 2>&1 | sed -n '1p')"
  if [[ ! "$java_version" =~ \"25\. ]]; then
    echo "Java 25 is required; found: $java_version" >&2
    exit 1
  fi

  backend_root="$deploy_home/ktb-chat-backend"
  install -d -o "$deploy_user" -g "$deploy_user" \
    "$backend_root" "$backend_root/releases" "$backend_root/uploads"

  unit_file="$(mktemp)"
  cat >"$unit_file" <<EOF
[Unit]
Description=KTB Chat backend
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=$deploy_user
Group=$deploy_user
WorkingDirectory=$backend_root/current
EnvironmentFile=$backend_root/.env
ExecStart=$java_bin -Xms512m -Xmx1024m -jar $backend_root/current/target/ktb-chat-backend-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=60
SuccessExitStatus=143
KillSignal=SIGTERM
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF
  install -m 644 "$unit_file" /etc/systemd/system/ktb-backend.service
  rm -f "$unit_file"
  systemctl enable ktb-backend.service
  echo "Installed ktb-backend.service (Java: $java_bin)"
}

case "$role" in
  frontend)
    install_frontend_unit
    ;;
  backend)
    install_backend_unit
    ;;
  all)
    install_frontend_unit
    install_backend_unit
    ;;
esac

systemctl_path="$(command -v systemctl)"
sudoers_file="/etc/sudoers.d/ktb-chat-deploy"
cat >"$sudoers_file" <<EOF
$deploy_user ALL=(root) NOPASSWD: $systemctl_path restart ktb-frontend.service, $systemctl_path restart ktb-backend.service
EOF
chmod 440 "$sudoers_file"
visudo -cf "$sudoers_file" >/dev/null

systemctl daemon-reload

cat <<EOF

EC2 systemd provisioning completed.
- Deploy user: $deploy_user
- Role: $role
- Services were enabled but not started because no release has been deployed yet.
- The first GitHub Actions deployment will create the current release symlink and start the service.
EOF
