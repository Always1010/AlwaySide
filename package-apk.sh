#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$script_dir/.local/android/package-release-upload.py" ]; then
    exec python3 -B "$script_dir/.local/android/package-release-upload.py" "$@"
fi
exec python3 -B "$script_dir/tools/package_apk.py" "$@"
