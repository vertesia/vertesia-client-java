# Agent Instructions

This repository contains the Java client for the Vertesia API. It combines a
small hand-written facade with a committed OpenAPI-generated client.

## Repository Layout

- `src/main/java/io/vertesia/VertesiaClient.java` is the hand-written
  high-level client facade. Prefer making public client behavior changes here.
- `src/main/java/io/vertesia/ClientOptions.java`,
  `src/main/java/io/vertesia/TokenSource.java`, and
  `src/main/java/io/vertesia/VertesiaClientException.java` support the
  hand-written facade.
- `src/main/java/io/vertesia/api/`, `src/main/java/io/vertesia/auth/`, and
  `src/main/java/io/vertesia/model/` are generated OpenAPI client code.
- Generated invoker files also live directly under `src/main/java/io/vertesia/`,
  including `ApiClient.java`, `JSON.java`, `Configuration.java`, request and
  response helpers, and server configuration classes.
- `spec/vertesia-openapi.json` and `spec/vertesia-openapi.yaml` are the tracked
  OpenAPI contract used by generation.
- `scripts/regenerate.sh` runs OpenAPI Generator, applies post-generation
  compatibility and security patches, formats code, and runs tests.
- `src/test/java/io/vertesia/` contains JUnit tests.
  `VertesiaClientLiveTest` contains opt-in live integration tests.
- `.github/workflows/` contains CI, release, generated-code guard, and zizmor
  workflow security checks. CodeQL uses GitHub default setup, so do not add a
  checked-in CodeQL workflow unless default setup is intentionally disabled.

## What You May Modify

Normal hand-written changes may touch:

- `src/main/java/io/vertesia/VertesiaClient.java`
- `src/main/java/io/vertesia/ClientOptions.java`
- `src/main/java/io/vertesia/TokenSource.java`
- `src/main/java/io/vertesia/VertesiaClientException.java`
- `src/test/`
- `README.md`, `AGENTS.md`, `CLAUDE.md`, and other docs
- `pom.xml` for package metadata, dependencies, plugin settings, or release
  version updates
- `openapi-generator-config.yaml` when changing generator configuration
- `scripts/`, especially generation compatibility and security patches
- `.github/` workflow and repository automation files

When changing public behavior, update or add tests in `src/test/` and keep the
README examples accurate.

## What You Must Not Modify Manually

Do not manually edit or commit generated files under:

- `src/main/java/io/vertesia/api/`
- `src/main/java/io/vertesia/auth/`
- `src/main/java/io/vertesia/model/`
- `spec/`
- `.openapi-generator/`

Most generated invoker files under `src/main/java/io/vertesia/` are also owned
by generation automation. `ApiClient.java` may be changed only when the change
is captured by reproducible post-generation patch logic, such as
`scripts/patch_generated_security.py`, in the same commit.

CI rejects normal pull requests that change generated paths. If generated output
is wrong, change the OpenAPI source upstream, generator configuration, or
post-generation patching logic, then let automation regenerate the client.

Do not commit local secrets or credentials:

- `.env` is for local development only.
- Live test credentials such as `VERTESIA_API_KEY` must never appear in tracked
  files, logs, fixtures, or examples.

## Generation Rules

The committed generated client exists so consumers can use releases without
running OpenAPI Generator.

Regeneration is not part of normal feature work. If regeneration is explicitly
needed, use:

```sh
scripts/regenerate.sh
```

This requires `openapi-generator` on `PATH`. The script runs:

```sh
openapi-generator generate -c openapi-generator-config.yaml
python3 scripts/patch_forward_compat_validation.py
python3 scripts/patch_generated_security.py
mvn -q spotless:apply test
```

Generated changes should be staged only by the dedicated generation automation,
using the expected generated paths and package metadata:

```sh
git add src/main/java/io/vertesia spec .openapi-generator pom.xml openapi-generator-config.yaml
```

For normal PRs, keep generated-code changes out of the commit unless the user
explicitly asks for a generation or post-generation patch change.

## Testing And Checks

Use Maven locally:

```sh
mvn -B test
mvn -B verify
mvn -B spotless:check
```

`mvn -B verify` is the local quality gate. It runs Maven enforcer rules, tests,
jar/source/javadoc packaging, and Spotless checks.

Live integration tests are skipped unless both conditions are true:

- `VERTESIA_LIVE_TESTS=1`
- `VERTESIA_API_KEY` is set to a non-placeholder `sk-` secret key

Live tests load `.env` when present and may contact Vertesia services. Do not
enable them unless the user explicitly asks and provides an appropriate
environment.

## Java Style

- Source and target compatibility are Java 8.
- CI runs on JDK 17 with Maven.
- Formatting is enforced by Spotless with Google Java Format in AOSP style.
- Keep generated-code formatting changes out of hand-written work.
- Prefer standard Java and existing dependencies for facade logic unless a new
  dependency is clearly part of the package contract.
- Keep Maven dependencies convergent; the enforcer plugin checks dependency
  convergence and duplicate dependency versions.
- Avoid broad dependency or plugin upgrades unless the user asks for that risk.

## Client Design Notes

- `VertesiaClient` is the recommended user entry point.
- The facade routes Studio and Store APIs through generated API groups.
- `apiKey` performs STS token exchange and requires an `sk-` secret key.
- `token` uses an existing bearer token and bypasses STS.
- `apiKey` and `token` are mutually exclusive.
- Custom split endpoints using `apiKey` require `tokenServerUrl` unless STS can
  be safely derived from a Vertesia `api*` host.
- The default `x-api-version` header is part of the client contract; update it
  deliberately and test header behavior.
- Generated models are patched for forward compatibility: unknown response
  fields are ignored and unknown enum values should not break consumers.
- TLS verification cannot be disabled. Use `ApiClient.setSslCaCert(...)` to
  trust a private certificate authority.

## Release And Version Rules

For release version changes, keep these values in sync:

- `pom.xml` project version
- `openapi-generator-config.yaml` `artifactVersion`
- `spec/vertesia-openapi.json` `info.version`

The release workflow verifies these values before publishing. Since `spec/` is
generated-owned, coordinate version changes with generation automation instead
of editing spec files manually in a normal PR.

The checked-in Maven project version should be the release version with a
`-SNAPSHOT` suffix. For example, releasing `v1.2.0` starts from
`1.2.0-SNAPSHOT` in `pom.xml`.

## GitHub Actions Security

Workflows are audited by `zizmor`. CodeQL is handled by GitHub default setup for
this repository; avoid adding a checked-in advanced CodeQL workflow while
default setup remains enabled.

- Pin third-party GitHub Actions by full commit SHA.
- Keep a trailing comment with the exact corresponding tag, for example
  `# v4.35.5`.
- For annotated tags, pin the peeled commit SHA, not the tag object SHA.
- Keep `permissions` minimal and prefer `permissions: {}` at workflow scope.
- Use `persist-credentials: false` for checkout unless a workflow explicitly
  needs push credentials.
- After workflow changes, run:

```sh
uvx zizmor@1.24.1 --no-exit-codes --persona=auditor .github
```

## Dependency Updates

Runtime dependencies and Maven plugin versions live in `pom.xml`. There is no
committed Maven lock file. Keep dependencies compatible with Java 8 and avoid
broad major version changes unless the user asks for that risk.

Dependabot also manages GitHub Actions updates. When updating workflow action
pins, resolve and pin the actual commit for the desired tag and update the tag
comment at the same time.

## Agent Workflow

- Check `git status --short --branch` before editing.
- Keep generated-code changes out of normal commits.
- Preserve unrelated user changes in the worktree.
- Prefer small, focused commits and describe verification performed.
- If you cannot run a relevant check because a tool or dependency is missing,
  state that clearly.
