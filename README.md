# Vertesia Java Client

Java client for the Vertesia API.

The client combines a small hand-written facade with OpenAPI-generated API and model classes. Application code should usually start with `VertesiaClient`; the generated APIs and models remain available under `io.vertesia.api` and `io.vertesia.model`.

Requires Java 17 or newer.

## Installation

The package coordinates are:

```xml
<dependency>
    <groupId>io.vertesia</groupId>
    <artifactId>vertesia-client</artifactId>
    <version>1.2.0</version>
</dependency>
```

## Usage

```java
import io.vertesia.ClientOptions;
import io.vertesia.VertesiaClient;

VertesiaClient client = new VertesiaClient(new ClientOptions()
        .setRegion("us1")
        .setApiKey("sk-..."));

var account = client.accounts.getCurrentAccount();
var projects = client.projects.listProjects(null);
```

`sk-` secret keys are exchanged through Vertesia STS and cached as bearer tokens. `pk-` keys are no longer supported. If you already have an issued bearer token, pass it directly:

```java
VertesiaClient client = new VertesiaClient(new ClientOptions()
        .setRegion("us1")
        .setToken("eyJ..."));
```

Do not set both `apiKey` and `token`.

## Endpoints

Use `region` for normal public regional routing:

```java
VertesiaClient client = new VertesiaClient(new ClientOptions()
        .setRegion("us1")
        .setApiKey("sk-..."));
```

Use `preview` with a region to target the preview API:

```java
VertesiaClient client = new VertesiaClient(new ClientOptions()
        .setRegion("us1")
        .setPreview(true)
        .setApiKey("sk-..."));
```

Use `site` when you need to pass an exact API host:

```java
VertesiaClient client = new VertesiaClient(new ClientOptions()
        .setSite("api.us1.vertesia.io")
        .setApiKey("sk-..."));
```

`serverUrl`, `storeUrl`, and `tokenServerUrl` are developer-only overrides for local or split-service testing.

## Generated APIs And Models

The high-level client exposes generated API groups:

```java
client.studio.projects.listProjects(null);
client.store.objects.searchObjects(payload);
```

It also exposes convenience aliases when routing is unambiguous:

```java
client.projects.listProjects(null);
client.objects.searchObjects(payload);
```

Generated model classes can be used directly:

```java
import io.vertesia.model.ComplexSearchPayload;

ComplexSearchPayload payload = new ComplexSearchPayload();
```

## Regeneration

Generated source is committed so the repository is directly usable by Java consumers. The source of truth is still the OpenAPI spec generated from the private Studio repository.

To regenerate locally:

```sh
openapi-generator generate -c openapi-generator-config.yaml
python3 scripts/patch_forward_compat_validation.py
python3 scripts/patch_generated_security.py
python3 scripts/patch_generated_jakarta_annotations.py
mvn -B spotless:apply test
```

The internal Studio workflow syncs generated Java source and `spec/` into this repository after the OpenAPI spec is validated on `main`. Hand-written client files, tests, workflows, README, LICENSE, NOTICE, `.env.example`, and generator config are protected by `.openapi-generator-ignore`.

## Tests

Run offline tests:

```sh
mvn -B test
```

## Code Quality

Run the local quality gate:

```sh
mvn -B verify
```

Format generated and hand-written sources:

```sh
mvn -B spotless:apply
```

Check formatting without modifying files:

```sh
mvn -B spotless:check
```

Run build hygiene checks only:

```sh
mvn -B validate
```

Live tests are opt-in and load `.env` when present:

```sh
VERTESIA_LIVE_TESTS=1 mvn -B test
```

Required live-test variables:

```text
VERTESIA_API_KEY=sk-...
VERTESIA_STUDIO_URL=https://api-preview.us1.vertesia.io/api/v1
VERTESIA_ZENO_URL=https://api-preview.us1.vertesia.io/api/v1
VERTESIA_STS_URL=https://sts.us1.vertesia.io
```

The public CI test workflow runs offline tests. The integration workflow resolves the test `sk-` key from Google Secret Manager through GitHub OIDC.

## Release

Releases are manual. The release workflow verifies the requested version against `pom.xml`, `openapi-generator-config.yaml`, and `spec/vertesia-openapi.json`, creates an annotated git tag, and publishes a GitHub Release.

The checked-in Maven project version should be the release version with a `-SNAPSHOT` suffix. For example, releasing `v1.2.0` starts from `1.2.0-SNAPSHOT` in `pom.xml`.

Maven releases can be prepared locally with the Maven Release Plugin. Run a dry run before creating tags or changing versions:

```sh
mvn -B release:prepare -DdryRun=true
```

Prepare the release tag and next development version:

```sh
mvn -B release:prepare
```

Deploy the tagged release:

```sh
mvn -B release:perform
```

`release:prepare` runs `clean verify`, which includes the Spotless and Enforcer checks. `release:perform` runs `deploy`, so Maven repository credentials and deployment configuration must be available before publishing.

The release workflow can upload release artifacts to Maven Central Portal. It requires these GitHub secrets:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
MAVEN_GPG_PRIVATE_KEY
MAVEN_GPG_PASSPHRASE
```

By default, the workflow uploads and waits for Central validation, but leaves the deployment unpublished for manual review in the Central Portal. Set `maven_central_auto_publish` when dispatching the workflow to publish automatically after validation.
