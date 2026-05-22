#!/usr/bin/env bash
set -euo pipefail

openapi-generator generate -c openapi-generator-config.yaml
python3 scripts/patch_forward_compat_validation.py
python3 scripts/patch_generated_security.py
python3 scripts/patch_generated_jakarta_annotations.py
mvn -q spotless:apply test
