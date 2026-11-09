package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertesia.model.SubscriptionWorkflowCatalog;
import org.junit.jupiter.api.Test;

class SubscriptionWorkflowCatalogDeserializationTest {
    @Test
    void preservesVariableSchemasAndTemplates() throws Exception {
        var catalog =
                SubscriptionWorkflowCatalog.fromJson(
                        """
            {"workflows":[{"endpoint":"StandardIntake","title":"Standard Intake","description":"Process content",
            "variables_schema":{"type":"object","properties":{"forceGeneration":{"type":"boolean"}}},
            "variables_template":{"forceGeneration":false}}]}
            """);
        assertEquals("StandardIntake", catalog.getWorkflows().get(0).getEndpoint());
        assertNotNull(catalog.getWorkflows().get(0).getVariablesSchema());
        assertEquals(
                false, catalog.getWorkflows().get(0).getVariablesTemplate().get("forceGeneration"));
    }

    @Test
    void rejectsMissingWorkflowMetadata() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SubscriptionWorkflowCatalog.fromJson(
                                "{\"workflows\":[{\"endpoint\":\"StandardIntake\"}]}"));
    }
}
