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

# The service is stopped, which ends everything it started. What can still be running is a server someone started by hand
# (komm-server, or java -jar on the jar) or one the service could not stop: it would go on using files that are about to be
# removed, and its database and ports would stay up. Find it by what it runs (the stub, anything under the data folder:
# PostgreSQL, LiveKit) and by the jar in app/, ask it to stop, and force it after a few seconds.
stop_leftovers() {
  local pids=() pid exe
  for pid in $(ls /proc | grep -E '^[0-9]+$'); do
    [[ "$pid" == "$$" ]] && continue
    exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    case "$exe" in
      "$DATA_DIR"/*|"$PREFIX"/komm-server|"$PREFIX"/komm-server-*)
        pids+=("$pid"); continue ;;
    esac
    if tr '\0' ' ' 2>/dev/null < "/proc/$pid/cmdline" | grep -qF "$PREFIX/app/"; then
      pids+=("$pid")
    fi
  done
  [[ ${#pids[@]} -eq 0 ]] && return 0
  echo "Stopping ${#pids[@]} process(es) still running from $PREFIX ..."
  kill -TERM "${pids[@]}" 2>/dev/null || true
  for _ in $(seq 1 20); do
    local alive=0
    for pid in "${pids[@]}"; do kill -0 "$pid" 2>/dev/null && alive=1; done
    [[ $alive -eq 0 ]] && return 0
    sleep 1
  done
  kill -KILL "${pids[@]}" 2>/dev/null || true
}
stop_leftovers

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
