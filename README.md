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
scripts/regenerate.sh
```

After any OpenAPI generation path, run `scripts/post_generate.sh` before
testing or committing generated output. It is the shared post-generation hook
used by local regeneration and the internal Studio sync workflow.

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

## OIDC federation for provisioned users

Use one facade per user/project/resource/scope combination. The user must already be provisioned
through SCIM and the organization must configure a federation trust policy. No user creation occurs.

```java
FederationOptions policy = new FederationOptions(
    "connection-id", "project-id", "https://api.us1.vertesia.io",
    () -> getFreshIdpToken());
// policy = policy.withScope("content:read"); // omit for current delegable permissions
ClientOptions options = new ClientOptions().setRegion("us1").setFederation(policy);
VertesiaClient client = new VertesiaClient(options);
// Use client.projects, client.objects, etc. as usual.

// One-shot exchange; caller manages the lifetime:
FederationToken issued = VertesiaClient.exchangeFederationToken(options, policy, idpAssertion);
// issued.accessToken(), issued.expiresIn(), issued.scope()
```

The first authenticated request exchanges an assertion. The facade shares its cached token between
Studio and Store, obtaining a fresh assertion after 90% of the token lifetime on the next request.
Renewal is synchronized across threads. There is no refresh token, background worker or automatic
replay of API writes on a 401. A failed renewal fails the request without sending expired credentials.
The provider must obtain a currently valid IdP token whenever called. Federation, `apiKey` and
`token` options are mutually exclusive.

Subject assertions default to `urn:ietf:params:oauth:token-type:jwt`; the full record constructor
also accepts ID/access token types. The endpoint defaults to resolved STS plus `/oauth/token`;
use `policy.withTokenEndpoint(...)` for the complete tenant endpoint, or explicit `tokenServerUrl`
for custom split URLs. HTTPS is required except loopback HTTP, and redirects are rejected.

Optional middleware authentication uses `policy.withClientSecret(id, secret)` for
`client_secret_post`, `withClientSecretBasic(id, secret)` for Basic, or
`withClientAssertion(id, supplier)` for `private_key_jwt`. The supplier must mint a fresh short-lived
assertion with a unique `jti` on each exchange, with a single-string `aud` equal to the Vertesia
authorization-server issuer from discovery (including the tenant). Secrets and tokens are redacted
from the new record representations and exchange errors.
