package io.vertesia;

import java.util.function.Supplier;

/** Immutable policy for one user, project, resource and scope. Providers return fresh assertions. */
public record FederationOptions(
        String connectionId,
        String projectId,
        String resource,
        Supplier<String> subjectTokenProvider,
        String scope,
        String subjectTokenType,
        String tokenEndpoint,
        String clientId,
        String clientSecret,
        Supplier<String> clientAssertionProvider,
        boolean clientSecretBasic) {
    /** Construct a secretless policy using JWT subject assertions. */
    public FederationOptions(
            String connectionId, String projectId, String resource, Supplier<String> provider) {
        this(
                connectionId,
                projectId,
                resource,
                provider,
                null,
                "urn:ietf:params:oauth:token-type:jwt",
                null,
                null,
                null,
                null,
                false);
    }

    /** Return a policy with a narrower requested scope. */
    public FederationOptions withScope(String value) {
        return new FederationOptions(
                connectionId,
                projectId,
                resource,
                subjectTokenProvider,
                value,
                subjectTokenType,
                tokenEndpoint,
                clientId,
                clientSecret,
                clientAssertionProvider,
                clientSecretBasic);
    }

    /** Override the complete token endpoint, including any tenant path. */
    public FederationOptions withTokenEndpoint(String value) {
        return new FederationOptions(
                connectionId,
                projectId,
                resource,
                subjectTokenProvider,
                scope,
                subjectTokenType,
                value,
                clientId,
                clientSecret,
                clientAssertionProvider,
                clientSecretBasic);
    }

    /** Authenticate the middleware with client_secret_post. */
    public FederationOptions withClientSecret(String id, String secret) {
        return new FederationOptions(
                connectionId,
                projectId,
                resource,
                subjectTokenProvider,
                scope,
                subjectTokenType,
                tokenEndpoint,
                id,
                secret,
                null,
                false);
    }

    /** Authenticate with a newly signed private_key_jwt on each exchange. */
    public FederationOptions withClientAssertion(String id, Supplier<String> provider) {
        return new FederationOptions(
                connectionId,
                projectId,
                resource,
                subjectTokenProvider,
                scope,
                subjectTokenType,
                tokenEndpoint,
                id,
                null,
                provider,
                false);
    }

    /** Authenticate the middleware with form-encoded HTTP Basic credentials. */
    public FederationOptions withClientSecretBasic(String id, String secret) {
        return new FederationOptions(
                connectionId,
                projectId,
                resource,
                subjectTokenProvider,
                scope,
                subjectTokenType,
                tokenEndpoint,
                id,
                secret,
                null,
                true);
    }

    @Override
    public String toString() {
        return "FederationOptions[credentials redacted]";
    }
}
