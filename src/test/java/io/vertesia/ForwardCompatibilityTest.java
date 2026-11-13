package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.vertesia.model.AgentRunResponse;
import io.vertesia.model.AgentRunStatus;
import io.vertesia.model.CompletionResult;
import io.vertesia.model.EventDeliveryTarget;
import io.vertesia.model.McpConnectionParameter;
import io.vertesia.model.McpSecretParameter;
import io.vertesia.model.ModelOptions;
import io.vertesia.model.NodeDefinition;
import io.vertesia.model.NodeDefinitionBranchesInner;
import io.vertesia.model.StatelessExecutionOptions;
import io.vertesia.model.TextResult;
import io.vertesia.model.TimerTask;
import io.vertesia.model.VertexAIClaudeOptions;
import io.vertesia.model.WorkflowTasksHistory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A released client must keep decoding responses from a newer server, which can add a property, an
 * enum value or a union variant without a breaking API change. The union cases depend on
 * scripts/patch_forward_compat_validation.py, which the Test workflow runs before these tests.
 */
class ForwardCompatibilityTest {
    private static final String RUN =
            "{\"id\":\"000000000000000000000001\",\"account\":\"000000000000000000000002\","
                    + "\"project\":\"000000000000000000000003\",\"interaction\":\"sys:GeneralAgent\","
                    + "\"interactionRef\":{\"id\":\"sys:GeneralAgent\",\"name\":\"sys:GeneralAgent\","
                    + "\"endpoint\":\"sys:GeneralAgent\",\"status\":\"code\",\"version\":0,\"tags\":[],"
                    + "\"updated_at\":\"1970-01-01T00:00:00.000Z\",\"future_ref_field\":1},"
                    + "\"run_kind\":\"agent\",\"run_type\":\"RUN_TYPE\",\"status\":\"STATUS\","
                    + "\"started_by\":\"user:000000000000000000000004\","
                    + "\"started_at\":\"2026-07-28T11:08:41.127Z\","
                    + "\"created_at\":\"2026-07-28T11:08:41.128Z\","
                    + "\"updated_at\":\"2026-07-29T10:59:13.166Z\","
                    + "\"future_top_level_field\":{\"nested\":true}}";

    private static String run(String runType, String status) {
        return RUN.replace("RUN_TYPE", runType).replace("STATUS", status);
    }

    private static void assertKeptRaw(String body, Object instance, JsonElement raw, String json) {
        JsonElement expected = JsonParser.parseString(body);
        assertEquals(expected, instance);
        assertEquals(expected, raw);
        assertEquals(expected, JsonParser.parseString(json));
    }

    @Test
    void unknownPropertiesAreIgnored() {
        AgentRunResponse run =
                JSON.deserialize(run("autonomous", "completed"), AgentRunResponse.class);
        assertEquals(
                "sys:GeneralAgent", run.getAutonomousRunResponse().getInteractionRef().getName());

        CompletionResult result =
                JSON.deserialize(
                        "{\"type\":\"text\",\"value\":\"hello\",\"future\":{\"a\":1}}",
                        CompletionResult.class);
        assertEquals("hello", result.getTextResult().getValue());
    }

    @Test
    void unknownEnumValueBecomesThePlaceholder() {
        // Known limitation: a Java enum cannot hold a value it does not declare, so a
        // read-modify-write sends the placeholder back instead of the server's value.
        AgentRunResponse run =
                JSON.deserialize(run("autonomous", "hibernating"), AgentRunResponse.class);
        assertEquals(
                AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API,
                run.getAutonomousRunResponse().getStatus());
        assertEquals(
                "unknown_default_open_api",
                JsonParser.parseString(run.toJson()).getAsJsonObject().get("status").getAsString());
    }

    @Test
    void unknownDiscriminatedVariantIsKeptRaw() {
        String body = "{\"type\":\"speech\",\"value\":\"gs://bucket/a.ogg\",\"voice\":\"alloy\"}";
        CompletionResult result = JSON.deserialize(body, CompletionResult.class);
        assertKeptRaw(body, result.getActualInstance(), result.getRaw(), result.toJson());

        // One unknown element must not fail the list around it.
        List<CompletionResult> results =
                JSON.getGson()
                        .fromJson(
                                "[{\"type\":\"text\",\"value\":\"a\"}," + body + "]",
                                new TypeToken<List<CompletionResult>>() {}.getType());
        assertInstanceOf(TextResult.class, results.get(0).getActualInstance());
        assertNull(results.get(0).getRaw());
        assertNotNull(results.get(1).getRaw());

        String runBody = run("scheduled", "completed");
        AgentRunResponse run = JSON.deserialize(runBody, AgentRunResponse.class);
        assertKeptRaw(runBody, run.getActualInstance(), run.getRaw(), run.toJson());

        String targetBody = "{\"type\":\"slack\",\"url\":\"https://hooks.slack.com/x\"}";
        EventDeliveryTarget target = JSON.deserialize(targetBody, EventDeliveryTarget.class);
        assertKeptRaw(targetBody, target.getActualInstance(), target.getRaw(), target.toJson());

        // Through a parent model, whose validation runs the union's validation.
        String timer =
                "{\"type\":\"timer\",\"activityId\":\"a\",\"scheduled\":null,\"status\":\"completed\","
                        + "\"attempts\":1,\"started\":null,\"completed\":null,\"error\":null,\"result\":null}";
        String update = "{\"type\":\"update\",\"activityId\":\"b\",\"update_name\":\"approve\"}";
        WorkflowTasksHistory history =
                JSON.deserialize(
                        "{\"type\":\"tasks\",\"tasks\":[" + timer + "," + update + "]}",
                        WorkflowTasksHistory.class);
        assertInstanceOf(TimerTask.class, history.getTasks().get(0).getActualInstance());
        assertEquals(JsonParser.parseString(update), history.getTasks().get(1).getRaw());

        // Known variants stay strict, and a missing discriminator is still an error.
        for (String invalid :
                List.of(
                        "{}",
                        "{\"value\":\"missing type\"}",
                        "{\"type\":\"text\"}",
                        "{\"type\":7}")) {
            assertThrows(
                    RuntimeException.class,
                    () -> JSON.deserialize(invalid, CompletionResult.class),
                    invalid);
        }
    }

    private static JsonObject modelOptionsOf(String options) {
        StatelessExecutionOptions execution =
                JSON.deserialize(
                        "{\"model\":\"gemini\",\"model_options\":" + options + "}",
                        StatelessExecutionOptions.class);
        return JsonParser.parseString(execution.toJson())
                .getAsJsonObject()
                .getAsJsonObject("model_options");
    }

    @Test
    void modelOptionsKeepEveryReceivedOption() {
        // Stored options are often merged from several families (a run's over its interaction's).
        // Only VertexAIGeminiOptions declares all these keys, but its person_generation enum lacks
        // allow_adult, which the typed model would rewrite: the set is kept raw.
        String gemini =
                "{\"temperature\":0.2,\"thinking_budget_tokens\":2048,\"effort\":\"high\","
                        + "\"person_generation\":\"allow_adult\"}";
        // This one fits no family: Claude's cache_ttl and an image size beside Gemini options.
        String mixed =
                "{\"temperature\":0.2,\"thinking_budget_tokens\":2048,\"cache_ttl\":\"1h\","
                        + "\"size\":\"1024x1024\"}";
        for (String options : List.of(gemini, mixed, tagged(gemini), tagged(mixed))) {
            JsonObject received = JsonParser.parseString(options).getAsJsonObject();
            JsonObject written = modelOptionsOf(options);
            for (String key : received.keySet()) {
                assertEquals(received.get(key), written.get(key), options);
            }
            ModelOptions decoded = JSON.deserialize(options, ModelOptions.class);
            assertEquals(received, decoded.getRaw());
        }

        // A single family stays typed, selected by its _option_id rather than by branch order.
        ModelOptions claude =
                JSON.deserialize(
                        "{\"_option_id\":\"vertexai-claude\",\"temperature\":0.2}",
                        ModelOptions.class);
        assertInstanceOf(VertexAIClaudeOptions.class, claude.getActualInstance());
        ModelOptions geminiOnly =
                JSON.deserialize(
                        "{\"temperature\":0.2,\"thinking_budget_tokens\":2048,\"effort\":\"high\"}",
                        ModelOptions.class);
        assertNull(geminiOnly.getRaw());
        McpConnectionParameter secret =
                JSON.deserialize(
                        "{\"type\":\"secret\",\"title\":\"k\"}", McpConnectionParameter.class);
        assertInstanceOf(McpSecretParameter.class, secret.getActualInstance());
    }

    private static String tagged(String options) {
        return "{\"_option_id\":\"vertexai-gemini\"," + options.substring(1);
    }

    @Test
    void unknownUndiscriminatedVariantIsKeptRaw() {
        String body = "{\"loop\":{\"over\":\"items\"},\"node\":7}";
        NodeDefinitionBranchesInner branch =
                JSON.deserialize(body, NodeDefinitionBranchesInner.class);
        assertKeptRaw(body, branch.getActualInstance(), branch.getRaw(), branch.toJson());

        NodeDefinition node =
                JSON.deserialize(
                        "{\"type\":\"agent\",\"branches\":[{\"to\":\"next\"}," + body + "]}",
                        NodeDefinition.class);
        assertNull(node.getBranches().get(0).getRaw());
        assertEquals(JsonParser.parseString(body), node.getBranches().get(1).getRaw());

        McpConnectionParameter parameter =
                JSON.deserialize("[\"oauth\"]", McpConnectionParameter.class);
        assertKeptRaw(
                "[\"oauth\"]",
                parameter.getActualInstance(),
                parameter.getRaw(),
                parameter.toJson());
    }
}
