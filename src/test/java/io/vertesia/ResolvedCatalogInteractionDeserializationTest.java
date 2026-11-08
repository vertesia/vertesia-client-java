package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertesia.model.ResolvedCatalogInteraction;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the normalized fields promised by the catalog resolve response. */
class ResolvedCatalogInteractionDeserializationTest {

    private static final String VALID =
            """
            {
              "type": "sys",
              "id": "sys:GeneralAgent",
              "name": "GeneralAgent",
              "title": "General Agent",
              "tags": ["agent"],
              "prompts": []
            }
            """;

    @Test
    void deserializesNormalizedTitleAndTags() {
        ResolvedCatalogInteraction parsed =
                JSON.getGson().fromJson(VALID, ResolvedCatalogInteraction.class);

        assertEquals("General Agent", parsed.getTitle());
        assertEquals(List.of("agent"), parsed.getTags());
    }

    @Test
    void rejectsTheLegacyIncompleteResponseShape() {
        String missingNormalizedFields =
                VALID.replace("\"title\": \"General Agent\",", "")
                        .replace("\"tags\": [\"agent\"],", "");

        assertThrows(
                Exception.class,
                () ->
                        JSON.getGson()
                                .fromJson(
                                        missingNormalizedFields, ResolvedCatalogInteraction.class));
    }
}
