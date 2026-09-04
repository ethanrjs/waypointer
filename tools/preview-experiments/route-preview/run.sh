#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 /path/to/test-runtime-classpath.txt" >&2
    exit 2
fi
preview_classpath=$(cat "$1")
preview_source_dir=$(cd -- "$(dirname -- "$0")" && pwd)
preview_build_dir=$(mktemp -d)
trap 'rm -rf -- "$preview_build_dir"' EXIT
javac -cp "$preview_classpath" -d "$preview_build_dir" \
    "$preview_source_dir/RoutePreviewSceneBenchmark.java"
java -cp "$preview_build_dir:$preview_classpath" RoutePreviewSceneBenchmark
