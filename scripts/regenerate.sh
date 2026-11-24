#!/usr/bin/env bash
set -euo pipefail

# The generator release is pinned in generator/Dockerfile, which Dependabot keeps current.
generator_image="$(sed -n 's/^FROM //p' generator/Dockerfile)"

# VertesiaClient sends x-api-version as a default header. With request builders the generator would otherwise
# add it to every operation as a required, ignored argument, so generate from a copy of the spec without it.
mkdir -p target
jq 'def strip: if has("parameters") then .parameters |= map(select((.in == "header" and .name == "x-api-version") | not)) else . end;
    .paths |= map_values(strip | map_values(if type == "object" then strip else . end))' \
    spec/vertesia-openapi.json > target/generator-spec.json

docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/local" -w /local "$generator_image" \
    generate -c openapi-generator-config.yaml -i target/generator-spec.json
scripts/post_generate.sh
mvn -B test
