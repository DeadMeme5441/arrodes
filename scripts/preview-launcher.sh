#!/bin/sh
set -eu
root=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
sandbox=$(mktemp -d "${TMPDIR:-/tmp}/arrodes-preview.XXXXXX")
trap 'rm -rf "$sandbox"' 0
trap 'exit 130' INT
trap 'exit 143' TERM HUP
mkdir -p "$sandbox/user" "$sandbox/project" "$sandbox/tmp"
cd "$sandbox/project"
env -i HOME="$sandbox/user" USERPROFILE="$sandbox/user" \
  PATH="/usr/bin:/bin" TERM="${TERM:-xterm-256color}" LANG="${LANG:-C.UTF-8}" \
  TMPDIR="$sandbox/tmp" XDG_CONFIG_HOME="$sandbox/user/config" \
  XDG_CACHE_HOME="$sandbox/user/cache" AWS_EC2_METADATA_DISABLED=true \
  "$root/arrodes-preview" --home "$sandbox/state" --cwd "$sandbox/project" "$@"
