#!/usr/bin/env bash

# Installs only the runtime required by the selected application role on Ubuntu.
# The caller must run scripts/provision-ec2-systemd.sh afterwards.

set -euo pipefail

role="${1:-}"
deploy_user="${2:-}"
java_version="${JAVA_VERSION:-25.0.3-librca}"

usage() {
  echo "Usage: sudo $0 <frontend|backend|all> <deploy-user>" >&2
}

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
  echo "Run this script with sudo." >&2
  exit 1
fi

case "$role" in
  frontend|backend|all) ;;
  *) usage; exit 1 ;;
esac

if [[ -z "$deploy_user" ]] || ! id "$deploy_user" >/dev/null 2>&1; then
  echo "Deploy user does not exist: $deploy_user" >&2
  exit 1
fi

install_base_packages() {
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y --no-install-recommends ca-certificates curl unzip zip
}

install_node() {
  local major
  major="$(node --version 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/' || true)"
  if [[ "$major" =~ ^[0-9]+$ && "$major" -ge 22 ]]; then
    echo "Node.js $(node --version) is already installed."
    return
  fi

  # NodeSource supplies the maintained Node.js 22 Ubuntu package. The deploy
  # service uses the resulting /usr/bin/node path, not a shell-only nvm setup.
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y --no-install-recommends nodejs
  echo "Installed Node.js $(node --version)."
}

install_java() {
  local current_java
  current_java="$(java -version 2>&1 | sed -n '1p' || true)"
  if [[ "$current_java" =~ \"25\. ]]; then
    echo "Java 25 is already installed: $current_java"
    return
  fi

  # This matches the Java distribution already used by apps/backend/Makefile.
  runuser -u "$deploy_user" -- bash -lc '
    set -e
    if [ ! -s "$HOME/.sdkman/bin/sdkman-init.sh" ]; then
      curl -fsSL https://get.sdkman.io | bash
    fi
    source "$HOME/.sdkman/bin/sdkman-init.sh"
    sdk install java "'"$java_version"'"
    sdk default java "'"$java_version"'"
  '

  current_java="$(runuser -u "$deploy_user" -- bash -lc 'source "$HOME/.sdkman/bin/sdkman-init.sh" && java -version 2>&1 | sed -n "1p"')"
  if [[ ! "$current_java" =~ \"25\. ]]; then
    echo "Java 25 installation failed; found: $current_java" >&2
    exit 1
  fi
  echo "Installed Java: $current_java"
}

install_base_packages

case "$role" in
  frontend)
    install_node
    ;;
  backend)
    install_java
    ;;
  all)
    install_node
    install_java
    ;;
esac
