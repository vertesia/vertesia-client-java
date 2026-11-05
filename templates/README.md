# Generator template overrides

`openapi-generator-config.yaml` sets `templateDir: ./templates`. OpenAPI Generator
reads a template from here when one exists and falls back to its built-in copy
otherwise.

## `libraries/okhttp-gson/api.mustache`

A copy of the built-in Java `okhttp-gson` API template from OpenAPI Generator
7.22.0 (the version in `.openapi-generator/VERSION`). The only change is the block
between `BEGIN vertesia` and `END vertesia`, appended to each operation.

For every operation with optional parameters, that block adds overloads of the
synchronous, `WithHttpInfo` and `Async` methods that take only the required
parameters and leave every optional parameter unset. The generator passes each
optional parameter positionally, so without these overloads, adding an optional
query parameter to an existing operation breaks every caller of that method at
compile time.

The overloads cover an operation going from no optional parameters to some. A
further optional parameter added to an operation that already has one still
changes the full signature.

When upgrading OpenAPI Generator, refresh the copy from the new version and
re-apply the marked block:

```sh
docker run --rm -v "$PWD/tmp-templates:/out" \
  openapitools/openapi-generator-cli:<version> \
  author template -g java --library okhttp-gson -o /out
```
