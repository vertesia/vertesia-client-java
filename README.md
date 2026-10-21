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

Use the Release workflow on `main` for timestamped dev cohorts or `release/X.Y`
for stable releases. `version` is the synchronized spec version with a leading
`v`; optional `source_sha` pins the tested commit. `dry_run=true` validates and
packages without tags or uploads.

The checked-in POM remains `<base>-SNAPSHOT`. The publisher stamps the artifact
version only in its build workspace, runs `mvn -B verify`, and tags the original
source commit. It does not push version-bump commits to the source branch.
For example, `v1.6.0-dev.20261021.123456Z` publishes Maven artifact version
`1.6.0-alpha.20261021.123456Z`; Maven's `alpha` qualifier sorts before the final
release. Dev GitHub releases are marked as prereleases.

Retries accept an existing tag only at the same source commit. An already
published Maven version is reused only when its matching source tag exists.

The release workflow can upload release artifacts to Maven Central Portal. It requires these GitHub secrets:

```text
MAVEN_CENTRAL_USERNAME
MAVEN_CENTRAL_PASSWORD
MAVEN_GPG_PRIVATE_KEY
MAVEN_GPG_PASSPHRASE
```

By default, the workflow publishes to Maven Central and waits for availability. Set
`maven_central_auto_publish=false` to leave the validated deployment for manual
publication in the Central Portal. Coordinated cohorts use automatic publication.

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

Exchange results report conservative remaining `expires_in` seconds after subtracting the STS
round trip (rounded up). Time spent obtaining IdP/client assertions does not consume the delegated
token's lifetime. Already-expired results are rejected in both one-shot and automatic modes.
Implicit STS routing is limited to recognized Vertesia STS domains; custom `api*` hostnames require
an explicit STS URL or complete token endpoint, even when their names resemble hosted endpoints.

An API 401 invalidates only the bearer token that request used. The SDK does not replay the request;
the next call attempts a fresh exchange. Concurrent late 401s do not evict a different newer token.
Failed automatic exchanges are cached for one second to avoid repeatedly calling the IdP/STS during
an outage. Finite lifetimes up to one day are accepted, including fractional seconds; returned
remaining seconds are rounded down. The server's current five-minute cap is not a client limit.

Catch `FederationExchangeException` and inspect `getStatusCode()` and `getErrorCode()` for retryable
429/503 responses or configuration errors. Only known OAuth codes are exposed; descriptions and
unknown remote codes remain redacted. Default one-shot calls reuse a shared HTTP connection pool.
