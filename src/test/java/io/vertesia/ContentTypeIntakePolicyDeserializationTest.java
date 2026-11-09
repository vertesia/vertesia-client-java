package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.vertesia.model.AnthropicClaudeOptions;
import io.vertesia.model.BedrockCohereCommandOptions;
import io.vertesia.model.ContentTypeIntakePolicy;
import io.vertesia.model.ContentTypeIntakePolicyExtraction;
import io.vertesia.model.ImagenOptions;
import io.vertesia.model.ModelOptions;
import io.vertesia.model.OpenAiTextOptions;
import io.vertesia.model.OpenRouterTextOptions;
import io.vertesia.model.VertexAIClaudeOptions;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Checks that the generated intake models preserve the fixture and its integer counters. */
class ContentTypeIntakePolicyDeserializationTest {

    private static ContentTypeIntakePolicy policy() {
        return JSON.getGson()
                .fromJson(
                        StudioResponseFixtures.read("content-type-intake-policy.json"),
                        ContentTypeIntakePolicy.class);
    }

    @Test
    void deserializesAFullPolicy() {
        ContentTypeIntakePolicy parsed = policy();
        assertNotNull(parsed);
        assertEquals(ContentTypeIntakePolicy.ModeEnum.PROGRAMMATIC, parsed.getMode());
        assertEquals("pdf", parsed.getDefaultView().getValue());
        assertNotNull(parsed.getIdentification());
        assertEquals(List.of("Q3 2025 investor letter"), parsed.getIdentification().getExamples());
    }

    @Test
    void mapsThePageAndDpiCountersToInteger() {
        // The whole point of the batch, in the type system that can see it. `Integer` rather than
        // `Long` is what `z.int32()` buys: retaining z.int()'s safe-integer bounds would have
        // generated 64-bit fields for a page count.
        ContentTypeIntakePolicyExtraction extraction = policy().getExtraction();
        Integer maxPages = extraction.getMaxPages();
        assertEquals(6, maxPages);
        assertEquals(9000, extraction.getVision().getMaxImageTokens());
        assertEquals(4, extraction.getVision().getMaxPagesPerCall());
        assertEquals(12, extraction.getGrounding().getMaxPages());
        assertEquals(4, extraction.getGrounding().getWindowPages());
        assertEquals(2, extraction.getVerification().getMaxRetries());
        assertEquals(150, policy().getTextConversion().getRenderDpi());
        assertEquals(10, policy().getLocate().getMinPages());
    }

    @Test
    void keepsPageRangesAsListsOfIntegers() {
        // `number[][]`, not a tuple: a Zod tuple emits prefixItems, which this generator renders as
        // a positional class rather than a list. Negative indexes count from the end of the
        // document, so the element type has to be signed.
        List<List<Integer>> ranges = policy().getExtraction().getPageRanges();
        assertEquals(List.of(List.of(1, 2), List.of(-1, -1)), ranges);
    }

    @Test
    void vertexClaudeModelOptionsKeepTheirNumericType() {
        var tree =
                JsonParser.parseString(
                        StudioResponseFixtures.read("content-type-intake-policy.json"));
        var options =
                JSON.getGson()
                        .fromJson(
                                tree.getAsJsonObject()
                                        .getAsJsonObject("extraction")
                                        .getAsJsonObject("config")
                                        .get("model_options"),
                                VertexAIClaudeOptions.class);
        assertEquals(VertexAIClaudeOptions.OptionIdEnum.VERTEXAI_CLAUDE, options.getOptionId());
        // BigDecimal, not Integer — and deliberately asserted as the contrast to the test above.
        // `ModelOptions` is declared in @llumiverse/common with `z.number()`, so its numeric fields
        // stay `number` in the document and BigDecimal in Java. Only the intake counters this batch
        // moved to `z.int32()` generate as Integer, which is what makes that change visible rather
        // than a blanket property of every converted schema.
        assertEquals(new BigDecimal("4096"), options.getMaxTokens());
    }

    @Test
    void deserializesOpenAiToolChoice() {
        OpenAiTextOptions options =
                JSON.getGson()
                        .fromJson(
                                "{\"_option_id\":\"openai-text\",\"tool_choice\":\"required\"}",
                                OpenAiTextOptions.class);
        assertEquals(OpenAiTextOptions.ToolChoiceEnum.REQUIRED, options.getToolChoice());
    }

    @Test
    void deserializesOpenRouterRoutingOptions() {
        String body =
                """
                {
                  "_option_id": "openrouter-text",
                  "provider_sort": "throughput",
                  "provider_order": ["google-vertex", "amazon-bedrock"],
                  "provider_allow_fallbacks": false
                }
                """;
        var options = JSON.getGson().fromJson(body, OpenRouterTextOptions.class);
        assertEquals(OpenRouterTextOptions.ProviderSortEnum.THROUGHPUT, options.getProviderSort());
        assertEquals(List.of("google-vertex", "amazon-bedrock"), options.getProviderOrder());
        assertEquals(false, options.getProviderAllowFallbacks());
    }

    @Test
    void acceptsUntaggedModelOptionsWithoutInventingAnId() {
        for (String body : List.of("{}", "{\"temperature\":0.2,\"max_tokens\":1024}")) {
            ModelOptions parsed = JSON.getGson().fromJson(body, ModelOptions.class);
            assertNotNull(parsed.getActualInstance());
            var expected = JsonParser.parseString(body).getAsJsonObject();
            var encoded = JsonParser.parseString(JSON.getGson().toJson(parsed)).getAsJsonObject();
            assertFalse(encoded.has("_option_id"));
            for (var field : expected.entrySet()) {
                assertEquals(field.getValue(), encoded.get(field.getKey()));
            }
        }
    }

    @Test
    void deserializesAnthropicOptions() {
        String body =
                """
                {"_option_id":"anthropic-claude","cache_enabled":true,"cache_ttl":"1h","effort":"high"}
                """;
        var options = JSON.getGson().fromJson(body, AnthropicClaudeOptions.class);
        assertEquals(true, options.getCacheEnabled());
    }

    @Test
    void preservesRestoredBedrockFields() {
        var options =
                JSON.getGson()
                        .fromJson(
                                "{\"_option_id\":\"bedrock-cohere-command\",\"top_k\":12,\"presence_penalty\":0.4,\"frequency_penalty\":0.2}",
                                BedrockCohereCommandOptions.class);
        assertEquals(new BigDecimal("12"), options.getTopK());
        assertEquals(new BigDecimal("0.4"), options.getPresencePenalty());
        assertEquals(new BigDecimal("0.2"), options.getFrequencyPenalty());
    }

    @Test
    void acceptsImagenProviderSpellingAndPublishedAlias() {
        for (String value : List.of("allow_adult", "allow_adults")) {
            var options =
                    JSON.getGson()
                            .fromJson(
                                    "{\"_option_id\":\"vertexai-imagen\",\"person_generation\":\""
                                            + value
                                            + "\",\"mask_class\":[0,42]}",
                                    ImagenOptions.class);
            assertEquals(value, options.getPersonGeneration().getValue());
            assertEquals(
                    List.of(new BigDecimal("0"), new BigDecimal("42")), options.getMaskClass());
        }
    }

    @Test
    void preservesOpenAiCompatibleExtraBodyMaps() {
        String body =
                """
                {
                  "_option_id": "openai-text",
                  "extra_body": {
                    "provider": {"sort": "throughput", "allow_fallbacks": false},
                    "baseten": {"performance": "max"}
                  }
                }
                """;
        var options = JSON.getGson().fromJson(body, OpenAiTextOptions.class);
        Map<?, ?> provider = assertInstanceOf(Map.class, options.getExtraBody().get("provider"));
        Map<?, ?> baseten = assertInstanceOf(Map.class, options.getExtraBody().get("baseten"));
        assertEquals("throughput", provider.get("sort"));
        assertEquals(false, provider.get("allow_fallbacks"));
        assertEquals("max", baseten.get("performance"));

        String invalid = "{\"_option_id\":\"openai-text\",\"extra_body\":[\"invalid\"]}";
        assertThrows(
                JsonSyntaxException.class,
                () -> JSON.getGson().fromJson(invalid, OpenAiTextOptions.class));
    }
}
