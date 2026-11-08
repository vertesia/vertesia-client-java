package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vertesia.model.CreateEventSubscriptionPayload;
import io.vertesia.model.WorkflowEventDeliveryTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntakeMediaAnalysisDeserializationTest {
    @Test
    void deserializesTypedMediaSettingsAndKeepsOtherWorkflowVariables() throws Exception {
        WorkflowEventDeliveryTarget intake =
                WorkflowEventDeliveryTarget.fromJson(
                        """
            {"type":"workflow","endpoint":"StandardIntake","vars":{"media_analysis":{
              "video":{"interaction":"custom:AnalyzeVideo",
                "config":{"inference_profile":"111111111111111111111111"}}}}}
            """);
        Map<?, ?> media = (Map<?, ?>) intake.getVars().get("media_analysis");
        Map<?, ?> video = (Map<?, ?>) media.get("video");
        Map<?, ?> config = (Map<?, ?>) video.get("config");
        assertEquals("custom:AnalyzeVideo", video.get("interaction"));
        assertEquals("111111111111111111111111", config.get("inference_profile"));
        WorkflowEventDeliveryTarget target =
                WorkflowEventDeliveryTarget.fromJson(
                        """
            {"type":"workflow","endpoint":"CustomWorker","vars":{"media_analysis":"custom-layout","useSemanticLayer":true}}
            """);
        assertEquals("custom-layout", target.getVars().get("media_analysis"));
        assertEquals(true, target.getVars().get("useSemanticLayer"));
    }

    @Test
    void deserializesFullReplacementIdentity() throws Exception {
        CreateEventSubscriptionPayload payload =
                CreateEventSubscriptionPayload.fromJson(
                        """
            {"name":"Normal intake","overrides_system_subscription_id":"sys:StandardIntake",
             "filter":{},"run_as_role":"automation","target":{"type":"workflow","endpoint":"StandardIntake"}}
            """);
        assertEquals("sys:StandardIntake", payload.getOverridesSystemSubscriptionId());
    }
}
