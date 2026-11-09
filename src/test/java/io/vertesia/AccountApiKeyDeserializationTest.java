package io.vertesia;

import static org.junit.jupiter.api.Assertions.*;

import io.vertesia.model.AccountApiKey;
import io.vertesia.model.AccountApiKeyWithValue;
import org.junit.jupiter.api.Test;

class AccountApiKeyDeserializationTest {
    private static final String METADATA =
            """
        {"id":"key","name":"IdP","account":"account","scope":"account","profile":"account_admin_v1",
        "enabled":true,"maskedValue":"sk-hidden","created_by":"user:admin","updated_by":"user:admin",
        "created_at":"2026-09-15T00:00:00Z","updated_at":"2026-09-15T00:00:00Z"}
        """;

    @Test
    void metadataNeedsNoProject() throws Exception {
        AccountApiKey key = AccountApiKey.fromJson(METADATA);
        assertEquals("IdP", key.getName());
        assertNotNull(key.getCreatedAt());
        assertFalse(key.toJson().contains("\"value\""));
        assertFalse(key.toJson().contains("\"project\""));
    }

    @Test
    void creationRequiresSecretAndMetadataRequiresProfile() {
        assertThrows(Exception.class, () -> AccountApiKeyWithValue.fromJson(METADATA));
        assertThrows(
                Exception.class,
                () ->
                        AccountApiKey.fromJson(
                                METADATA.replace("\"profile\":\"account_admin_v1\",", "")));
    }
}
