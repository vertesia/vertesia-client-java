#!/usr/bin/env bash
set -euo pipefail

# VertesiaClient sends x-api-version as a default header. With request builders the generator would otherwise
# add it to every operation as a required, ignored argument, so generate from a copy of the spec without it.
mkdir -p target
jq 'def strip: if has("parameters") then .parameters |= map(select((.in == "header" and .name == "x-api-version") | not)) else . end;
    .paths |= map_values(strip | map_values(if type == "object" then strip else . end))' \
    spec/vertesia-openapi.json > target/generator-spec.json

# SECURITY_SCHEMES_FILTER in openapi-generator-config.yaml needs 7.23.0; older releases ignore it and emit OAuth code.
generator_version="$(openapi-generator version)"
if [[ "$(printf '%s\n' 7.23.0 "$generator_version" | sort -V | head -1)" != 7.23.0 ]]; then
    echo "openapi-generator $generator_version is too old: 7.23.0 or later is required" >&2
    exit 1
fi

openapi-generator generate -c openapi-generator-config.yaml -i target/generator-spec.json
scripts/post_generate.sh
mvn -B test
