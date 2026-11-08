package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertesia.model.Project;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that shared project responses decode with typed timestamps and open integration maps. */
class ProjectDeserializationTest {

    private static Project project(String name) {
        return JSON.getGson().fromJson(StudioResponseFixtures.read(name), Project.class);
    }

    @Test
    void deserializesAFullyPopulatedProject() {
        Project parsed = project("project-populated.json");
        assertNotNull(parsed);
        assertEquals("68b1779130afe5403a1589ba", parsed.getId());
        assertEquals("Acme", parsed.getName());
        assertEquals("acme", parsed.getNamespace());
        assertEquals("A project", parsed.getDescription());
        assertEquals(List.of("plugin-a"), parsed.getPlugins());
        assertNotNull(parsed.getConfiguration());
        assertTrue(parsed.getConfiguration().getEmbeddings().getText().getEnabled());
    }

    @Test
    void keepsTheTimestampsAsOffsetDateTime() {
        // The whole point of the conversion, in the type system that can see it. `format:
        // date-time`
        // survived it — a plain `z.string()` in the Zod schema would have dropped the format and
        // narrowed this to `String`, which no TypeScript check could have caught.
        Project parsed = project("project-populated.json");
        OffsetDateTime createdAt = parsed.getCreatedAt();
        assertEquals(OffsetDateTime.parse("2026-07-30T10:00:00Z"), createdAt);
        assertEquals(OffsetDateTime.parse("2026-07-30T11:00:00Z"), parsed.getUpdatedAt());
    }

    @Test
    void readsIntegrationsAsAnOpenMap() {
        // Never a `Map<string, unknown>` on the wire — Mongoose flattens the model's map in
        // `toJSON`, so what a client receives is a plain object with per-integration configuration
        // of no common shape. That is why the component is an open map rather than a named union.
        Map<String, Object> integrations = project("project-populated.json").getIntegrations();
        assertNotNull(integrations);
        assertEquals(2, integrations.size());
        assertTrue(integrations.containsKey("github"));
        assertTrue(integrations.containsKey("serper"));
    }

    @Test
    void deserializesAProjectStoredBeforeTheModelHadTimestampsOrProvenance() {
        // The mapper supplies `created_at`, `updated_at`, `created_by` and `updated_by`, which is
        // what makes this response deserializable at all: all four are required by the closed
        // component, and the raw document has none of them.
        Project parsed = project("project-legacy-normalized.json");
        assertEquals(OffsetDateTime.parse("2017-11-29T03:36:28Z"), parsed.getCreatedAt());
        assertEquals(parsed.getCreatedAt(), parsed.getUpdatedAt());
        assertEquals("unknown", parsed.getCreatedBy());
        // The mapper omits `integrations` rather than sending null, which is what the closed
        // component requires — the property is not nullable. OpenAPI Generator 7.22 leaves that
        // absent property as null in Java, matching the Go client's nil map. Both represent the
        // same wire fact: the property was absent, not explicitly null.
        assertNull(parsed.getIntegrations());
        assertNull(parsed.getDescription());
    }

    @Test
    void keepsTenantIdAndAnyOtherUndeclaredPropertyOffTheDeclaredFields() {
        // `tenant_id` specifically: the model computes it and the handler used to ship it, which is
        // what `toProjectResponse` stops.
        //
        // The component is `additionalProperties: false`, but the generated Java model does not
        // enforce that: openapi-generator flips a model to permissive as soon as one of its
        // properties is a free-form map — `integrations` here — regardless of the root's own
        // closure. So the strongest thing a generated client can assert is that an undeclared
        // property lands in the extras bag instead of being folded onto a declared field. The
        // closure itself is enforced by AJV server-side, and the leak is caught by the mapper test
        // named in the class comment.
        String body =
                StudioResponseFixtures.read("project-populated.json")
                        .replaceFirst("\\{", "{\"tenant_id\": \"1589bb_1589ba\",");
        Project parsed = JSON.getGson().fromJson(body, Project.class);
        assertEquals("1589bb_1589ba", parsed.getAdditionalProperty("tenant_id"));
        assertEquals("68b1779130afe5403a1589ba", parsed.getId());
    }
}
