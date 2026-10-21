package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertesia.model.AccountProjectsResponse;
import io.vertesia.model.AccountType;
import io.vertesia.model.ContentObjectStatus;
import io.vertesia.model.ContentObjectType;
import io.vertesia.model.DataStoreFullSchemaResponse;
import io.vertesia.model.DataStoreSchemaResponse;
import org.junit.jupiter.api.Test;

class GeneratedCompatibilityTest {
    @Test
    void generatedModelsPreserveUnknownResponseFields() {
        ContentObjectType response =
                JSON.deserialize(
                        "{"
                                + "\"id\":\"type-1\","
                                + "\"name\":\"Default\","
                                + "\"edit_revision\":1,"
                                + "\"updated_by\":\"user-1\","
                                + "\"created_by\":\"user-1\","
                                + "\"created_at\":\"2026-01-01T00:00:00.000Z\","
                                + "\"updated_at\":\"2026-01-01T00:00:00.000Z\","
                                + "\"server_added_field\":{\"nested\":true}"
                                + "}",
                        ContentObjectType.class);

        assertEquals("type-1", response.getId());
        assertNotNull(response.getAdditionalProperties());
        assertNotNull(response.getAdditionalProperty("server_added_field"));
    }

    @Test
    void generatedModelsPreserveNestedUnknownResponseFields() {
        AccountProjectsResponse response =
                JSON.deserialize(
                        "{"
                                + "\"data\":[{"
                                + "\"id\":\"project-1\","
                                + "\"name\":\"Project One\","
                                + "\"account\":\"account-1\","
                                + "\"server_added_nested_field\":{\"ignored\":true}"
                                + "}],"
                                + "\"server_added_top_level_field\":\"ignored\""
                                + "}",
                        AccountProjectsResponse.class);

        assertEquals(1, response.getData().size());
        assertEquals("project-1", response.getData().get(0).getId());
    }

    @Test
    void generatedEnumsUseUnknownDefaultForUnknownValues() {
        assertEquals(
                ContentObjectStatus.UNKNOWN_DEFAULT_OPEN_API,
                ContentObjectStatus.fromValue("future-status"));
        assertEquals(
                AccountType.UNKNOWN_DEFAULT_OPEN_API, AccountType.fromValue("future-account-type"));
    }

    @Test
    void generatedClientRejectsDisabledTlsVerification() {
        ApiClient client = new ApiClient();

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> client.setVerifyingSsl(false));

        assertEquals(
                "Disabling TLS/SSL verification is not supported. Use setSslCaCert(...) to trust a custom CA certificate.",
                error.getMessage());
        assertTrue(client.isVerifyingSsl());
    }

    @Test
    void generatedUnionsHandleKnownShapeWithUnknownFields() {
        DataStoreSchemaResponse response =
                JSON.deserialize(
                        "{"
                                + "\"schema_format\":\"full\","
                                + "\"version\":\"1.0.0\","
                                + "\"tables\":[],"
                                + "\"relationships\":[],"
                                + "\"updated_at\":\"2026-01-01T00:00:00.000Z\","
                                + "\"server_added_field\":\"ignored\""
                                + "}",
                        DataStoreSchemaResponse.class);

        assertNotNull(response);
        assertTrue(response.getActualInstance() instanceof DataStoreFullSchemaResponse);
    }

    @Test
    void contentTypeReferenceLiteralsSelectOneBranch() {
        io.vertesia.model.ContentObjectTypeRef stored =
                JSON.deserialize(
                        "{\"ref_type\":\"stored\",\"id\":\"type-1\",\"name\":\"Test\",\"future\":true}",
                        io.vertesia.model.ContentObjectTypeRef.class);
        assertTrue(stored.getActualInstance() instanceof io.vertesia.model.StoredTypeRef);
        io.vertesia.model.ContentObjectTypeRef incode =
                JSON.deserialize(
                        "{\"ref_type\":\"incode\",\"id\":\"sys:Test\",\"name\":\"Test\",\"future\":true}",
                        io.vertesia.model.ContentObjectTypeRef.class);
        assertTrue(incode.getActualInstance() instanceof io.vertesia.model.InCodeTypeRef);
        assertThrows(
                com.google.gson.JsonSyntaxException.class,
                () ->
                        JSON.deserialize(
                                "{\"ref_type\":\"unknown\",\"id\":\"type-1\",\"name\":\"Test\"}",
                                io.vertesia.model.ContentObjectTypeRef.class));
    }

    @Test
    void bulkOperationLiteralSelectsUpdateBranch() {
        io.vertesia.model.BulkOperationResponse result =
                JSON.deserialize(
                        "{\"operation\":\"update\",\"status\":\"completed\",\"updated\":0,\"failed\":[]}",
                        io.vertesia.model.BulkOperationResponse.class);
        assertTrue(result.getActualInstance() instanceof io.vertesia.model.BulkObjectUpdateResult);
    }

    @Test
    void jsonSchemaAdditionalPropertiesDoesNotCollideWithExtraFieldMap() {
        var schema =
                JSON.deserialize(
                        "{\"type\":\"object\",\"additionalProperties\":false,\"future_field\":\"kept\"}",
                        io.vertesia.model.JSONSchema.class);
        var serialized =
                com.google.gson.JsonParser.parseString(JSON.serialize(schema)).getAsJsonObject();
        assertEquals(false, serialized.get("additionalProperties").getAsBoolean());
        assertEquals("kept", serialized.get("future_field").getAsString());
        assertNotNull(JSON.serialize(new io.vertesia.model.PromptTemplateCreatePayload()));
    }
}
