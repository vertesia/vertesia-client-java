package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import io.vertesia.model.AssignableRoleName;
import io.vertesia.model.CreateApiKeyPayload;
import io.vertesia.model.CustomRoleDefinition;
import io.vertesia.model.SystemRoles;
import io.vertesia.model.UpdateApiKeyPayload;
import io.vertesia.model.UpdateCustomRolePayload;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomRoleDeserializationTest {
    @Test
    void builtinRolesRemainStringsOnTheWire() throws Exception {
        for (SystemRoles role : SystemRoles.values()) {
            AssignableRoleName reference = new AssignableRoleName(role);
            assertEquals(JSON.getGson().toJson(role), reference.toJson());
            assertEquals(
                    reference.toJson(), AssignableRoleName.fromJson(reference.toJson()).toJson());
        }
    }

    @Test
    void writesCustomRoleReferencesInApiKeyRequests() throws Exception {
        AssignableRoleName reference = new AssignableRoleName("c:document_reviewer");
        assertEquals("\"c:document_reviewer\"", reference.toJson());

        CreateApiKeyPayload create = new CreateApiKeyPayload().name("Reviewer").role(reference);
        assertEquals(
                reference.toJson(),
                JsonParser.parseString(create.toJson()).getAsJsonObject().get("role").toString());

        UpdateApiKeyPayload update = new UpdateApiKeyPayload().role(reference);
        assertEquals(
                reference.toJson(),
                JsonParser.parseString(update.toJson()).getAsJsonObject().get("role").toString());
    }

    @Test
    void inheritedRolesRoundTripWithoutExpandingExplicitPermissions() throws Exception {
        var role =
                new CustomRoleDefinition()
                        .name("editor")
                        .title("Editor")
                        .permissions(List.of())
                        .baseRoles(
                                List.of(
                                        new AssignableRoleName(SystemRoles.AUDITOR),
                                        new AssignableRoleName("c:reviewer")));
        var encoded = JsonParser.parseString(role.toJson()).getAsJsonObject();
        assertEquals(0, encoded.getAsJsonArray("permissions").size());
        assertEquals(
                "[\"auditor\",\"c:reviewer\"]", encoded.getAsJsonArray("base_roles").toString());
        UpdateCustomRolePayload update = UpdateCustomRolePayload.fromJson("{\"base_roles\":[]}");
        assertEquals(List.of(), update.getBaseRoles());
        assertEquals(List.of(), UpdateCustomRolePayload.fromJson(update.toJson()).getBaseRoles());
    }
}
