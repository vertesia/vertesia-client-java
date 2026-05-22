#!/usr/bin/env bash
set -euo pipefail

openapi-generator generate -c openapi-generator-config.yaml
scripts/post_generate.sh
mvn -B test
