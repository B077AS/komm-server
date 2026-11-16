#!/usr/bin/env bash
# Installs komm-server on Linux, everything in one folder:
#
#   <prefix>/                       default /opt/komm-server
#     komm-server                   the jpack stub: the service runs it, it starts the server and swaps updates in
#     jpack.json  app/  launcher/   what is installed (managed by `kommserver update`)
#     data/                         everything the server writes (keys, database, uploads, logs); --data-dir moves it
#     uninstall.sh
#
# Outside it there is only the `kommserver` command (/usr/local/bin/kommserver) and the systemd unit it registers.
# Needs Java 21 or newer on the machine (no Java is bundled).
#
#   sudo ./install.sh [--prefix DIR] [--data-dir DIR] [--code CODE] [--no-start]
#
#   --code CODE   the verification code from the Komm client (asked for when missing and the server is not activated)
#   --no-start    install and register the service, but do not start it (to copy existing data in first)
set -euo pipefail

PREFIX=/opt/komm-server
DATA_DIR=""
CODE=""
START=1
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift 2 ;;
    --data-dir) DATA_DIR="$2"; shift 2 ;;
    --code) CODE="$2"; shift 2 ;;
    --no-start) START=0; shift ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 1 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Run this as root: sudo ./install.sh" >&2
  exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FILES="$HERE/files"
if [[ ! -x "$FILES/komm-server" ]]; then
  echo "$FILES/komm-server is missing: unpack the whole archive and run install.sh from it." >&2
  exit 1
fi

# --- Java 21+ -------------------------------------------------------------------------------------------------------
JAVA=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(readlink -f "$(command -v java)")"
fi
if [[ -z "$JAVA" ]]; then
  echo "Java 21 or newer is needed and none was found. Install it first, e.g.: sudo apt install openjdk-21-jre-headless" >&2
  exit 1
fi
SPEC="$("$JAVA" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2; exit}')"
if [[ -z "$SPEC" || "${SPEC%%.*}" -lt 21 ]]; then
  echo "$JAVA is Java ${SPEC:-?}; komm-server needs 21 or newer. Install it, or point JAVA_HOME at one." >&2
  exit 1
fi
JAVA_HOME_FOUND="$(dirname "$(dirname "$JAVA")")"
echo "Java ${SPEC}: $JAVA_HOME_FOUND"

# --- files ----------------------------------------------------------------------------------------------------------
PREFIX="$(realpath -m "$PREFIX")"
if [[ -f "$PREFIX/jpack.json" ]]; then
  echo "komm-server is already installed in $PREFIX. Update it with: sudo kommserver update" >&2
  exit 1
fi
mkdir -p "$PREFIX"
cp -a "$FILES/." "$PREFIX/"
install -m 755 "$HERE/uninstall.sh" "$PREFIX/uninstall.sh"
chmod 755 "$PREFIX/komm-server"

DATA_DIR="$(realpath -m "${DATA_DIR:-$PREFIX/data}")"
mkdir -p "$DATA_DIR"
chmod 700 "$DATA_DIR"

# --- the kommserver command -----------------------------------------------------------------------------------------
# Runs the tool through the installation's stub (JPACK_START=launcher), with the same Java and data folder as the
# service. This file is the only part of the installation outside the folder; `kommserver update` never changes it.
cat > /usr/local/bin/kommserver <<EOF
#!/bin/sh
# The kommserver command of the komm-server installed in $PREFIX (written by install.sh, removed by uninstall.sh).
export JAVA_HOME='$JAVA_HOME_FOUND'
export KOMM_DATA_DIR='$DATA_DIR'
export JPACK_START=launcher
exec '$PREFIX/komm-server' "\$@"
EOF
chmod 755 /usr/local/bin/kommserver
echo "Installed in $PREFIX (data: $DATA_DIR); the command is /usr/local/bin/kommserver"

# --- service --------------------------------------------------------------------------------------------------------
if [[ -n "$CODE" ]]; then
  kommserver install-service --code "$CODE"
else
  kommserver install-service
fi
if [[ $START -eq 1 ]]; then
  kommserver start
else
  echo "Not started (--no-start). Start it with: sudo kommserver start"
fi
