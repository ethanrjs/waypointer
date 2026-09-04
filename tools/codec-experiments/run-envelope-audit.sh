#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
    echo "Usage: JAVA_HOME=<Java 25 home> $0 <route-array.json> <runtime-classpath.txt> [output-directory]" >&2
    exit 2
fi

experiment_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
corpus_file="$1"
classpath_file="$2"
output_directory="${3:-/tmp/waypointer-codec-envelope}"
java_binary="${JAVA_HOME:+$JAVA_HOME/bin/}java"
javac_binary="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
runtime_classpath="$(cat "$classpath_file")"
mkdir -p "$output_directory/classes"

"$javac_binary" -cp "$runtime_classpath" -d "$output_directory/classes" \
    "$experiment_root/src/test/java/com/babbur/waypointer/codec/CodecRouteCorpus.java" \
    "$experiment_root/tools/codec-experiments/EnvelopeAudit.java" \
    "$experiment_root/tools/codec-experiments/EnvelopeRichAudit.java" \
    "$experiment_root/tools/codec-experiments/TinyEnvelopeAudit.java"

for experiment in EnvelopeAudit EnvelopeRichAudit TinyEnvelopeAudit; do
    case "$experiment" in
        EnvelopeAudit) output_name=results.csv ;;
        EnvelopeRichAudit) output_name=rich-results.csv ;;
        TinyEnvelopeAudit) output_name=tiny-version11-results.csv ;;
    esac
    "$java_binary" -cp "$output_directory/classes:$runtime_classpath" \
        "com.babbur.waypointer.codec.$experiment" "$corpus_file" "$output_directory/$output_name"
done
