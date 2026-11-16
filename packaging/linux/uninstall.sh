#!/usr/bin/env bash
# Removes komm-server: the service, the `kommserver` command and the installed files.
# The data folder (keys, database, uploads, logs) is kept unless --purge is given.
#
#   sudo ./uninstall.sh [--purge]
set -euo pipefail

PURGE=0
[[ "${1:-}" == "--purge" ]] && PURGE=1

if [[ $EUID -ne 0 ]]; then
  echo "Run this as root: sudo ./uninstall.sh" >&2
  exit 1
fi

PREFIX="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ ! -f "$PREFIX/jpack.json" ]]; then
  echo "$PREFIX does not look like a komm-server installation (no jpack.json): run the uninstall.sh inside it." >&2
  exit 1
fi
# The data folder the kommserver command was set up with.
DATA_DIR="$PREFIX/data"
if [[ -f /usr/local/bin/kommserver ]]; then
  FROM_WRAPPER="$(sed -n "s/^export KOMM_DATA_DIR='\(.*\)'$/\1/p" /usr/local/bin/kommserver)"
  [[ -n "$FROM_WRAPPER" ]] && DATA_DIR="$FROM_WRAPPER"
fi

if [[ -f /etc/systemd/system/komm-server.service ]]; then
  if command -v kommserver >/dev/null 2>&1; then
    kommserver uninstall-service || true
  fi
  # In case the command could not do it.
  systemctl stop komm-server 2>/dev/null || true
  systemctl disable komm-server 2>/dev/null || true
  rm -f /etc/systemd/system/komm-server.service
  systemctl daemon-reload
fi
rm -f /usr/local/bin/kommserver

# Everything jpack put there (and what it leaves while working), not the data folder.
rm -rf "$PREFIX/app" "$PREFIX/launcher" "$PREFIX/update" "$PREFIX/old" "$PREFIX/trash" \
       "$PREFIX/komm-server" "$PREFIX/komm-server.old" "$PREFIX"/jpack* "$PREFIX/kommserver-update.properties"

if [[ $PURGE -eq 1 ]]; then
  rm -rf "$DATA_DIR"
  echo "komm-server and its data ($DATA_DIR) are removed."
else
  echo "komm-server is removed. Its data is still in $DATA_DIR (sudo $0 --purge removes it too)."
fi
rm -f "$PREFIX/uninstall.sh"
rmdir "$PREFIX" 2>/dev/null || true
