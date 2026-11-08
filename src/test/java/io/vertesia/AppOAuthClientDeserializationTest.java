package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertesia.model.AppOAuthClientConfig;
import io.vertesia.model.OAuthClient;
import io.vertesia.model.OidcFederationConnectionPayload;
import org.junit.jupiter.api.Test;

class AppOAuthClientDeserializationTest {
    @Test
    void deserializesManifestCallbacksWithoutAnInstallationMode() {
        var config =
                JSON.getGson()
                        .fromJson(
                                "{\"redirect_uris\":[\"https://app.example/app\"]}",
                                AppOAuthClientConfig.class);
        assertEquals("https://app.example/app", config.getRedirectUris().get(0).toString());
    }

    @Test
    void rejectsMissingCallbacks() {
        assertThrows(
                Exception.class, () -> JSON.getGson().fromJson("{}", AppOAuthClientConfig.class));
    }

    private static final String SIGNED_CLIENT =
            """
            {"client_id":"vts_test","client_name":"Middleware","client_type":"confidential",
             "redirect_uris":["https://app.example/callback"],"grant_types":["authorization_code","urn:ietf:params:oauth:grant-type:token-exchange"],
             "response_types":["code"],"token_endpoint_auth_method":"private_key_jwt",
             "jwks_uri":"https://app.example/keys","allowed_scopes":["content:read"],
             "registration_source":"admin","status":"active","project_binding_mode":"user_select",
             "created_at":"2026-10-17T00:00:00Z","updated_at":"2026-10-17T00:00:00Z"}
            """;

    @Test
    void deserializesSignedClientRegistrationAndToleratesFutureMethods() {
        var client = JSON.getGson().fromJson(SIGNED_CLIENT, OAuthClient.class);
        assertEquals("private_key_jwt", client.getTokenEndpointAuthMethod().toString());
        assertEquals("https://app.example/keys", client.getJwksUri().toString());
        var future =
                JSON.getGson()
                        .fromJson(
                                SIGNED_CLIENT.replace("private_key_jwt", "unknown_method"),
                                OAuthClient.class);
        assertEquals("unknown_default_open_api", future.getTokenEndpointAuthMethod().toString());
    }

    @Test
    void deserializesFederationClientAllowlist() {
        var policy =
                JSON.getGson()
                        .fromJson(
                                """
                {"name":"Trust","issuer":"https://idp.example","external_id_claim":"sub",
                 "allowed_audiences":["middleware"],"allowed_algorithms":["RS256"],
                 "subject_token_type":"urn:ietf:params:oauth:token-type:jwt",
                 "allowed_project_ids":["012345678901234567890124"],"allowed_client_ids":["vts_test"],
                 "max_token_ttl_seconds":300,"enabled":false}
                """,
                                OidcFederationConnectionPayload.class);
        assertEquals("vts_test", policy.getAllowedClientIds().get(0));
    }

    @Test
    void deserializesAllProjectPolicyAndRejectsNonBooleanMode() {
        var json =
                """
                {"name":"Trust","issuer":"https://idp.example","external_id_claim":"sub",
                 "allowed_audiences":["middleware"],"allowed_algorithms":["RS256"],
                 "subject_token_type":"urn:ietf:params:oauth:token-type:jwt",
                 "all_projects":true,"allowed_project_ids":[],"max_token_ttl_seconds":300,"enabled":false}
                """;
        var policy = JSON.getGson().fromJson(json, OidcFederationConnectionPayload.class);
        assertEquals(true, policy.getAllProjects());
        assertEquals(0, policy.getAllowedProjectIds().size());
        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(
                                        json.replace(
                                                "\"all_projects\":true", "\"all_projects\":{}"),
                                        OidcFederationConnectionPayload.class));
    }
}
