package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.vertesia.model.AccountApiKey;
import io.vertesia.model.AgentEventDeliveryTarget;
import io.vertesia.model.AgentMessageType;
import io.vertesia.model.AgentRunResponse;
import io.vertesia.model.AgentRunStatus;
import io.vertesia.model.AskUserWebhookConfiguration;
import io.vertesia.model.AuditAction;
import io.vertesia.model.AuditAdoptionFilter;
import io.vertesia.model.CompactMessage;
import io.vertesia.model.EventDeliveryTarget;
import io.vertesia.model.KnownAuditAction;
import io.vertesia.model.ProjectIntegrationConfigResponse;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A newer server can send an enum value this client does not declare. The enum property still
 * decodes to {@code UNKNOWN_DEFAULT_OPEN_API}, and a read-modify-write sends back the value
 * received. These cases depend on scripts/patch_forward_compat_validation.py, which the Test
 * workflow runs before these tests.
 */
class UnknownEnumValuesTest {
    private static final String RUN =
            "{\"id\":\"000000000000000000000001\",\"account\":\"000000000000000000000002\","
                    + "\"project\":\"000000000000000000000003\",\"interaction\":\"sys:GeneralAgent\","
                    + "\"interactionRef\":{\"id\":\"sys:GeneralAgent\",\"name\":\"sys:GeneralAgent\","
                    + "\"endpoint\":\"sys:GeneralAgent\",\"status\":\"code\",\"version\":0,\"tags\":[],"
                    + "\"updated_at\":\"1970-01-01T00:00:00.000Z\"},"
                    + "\"run_kind\":\"agent\",\"run_type\":\"autonomous\",\"status\":\"hibernating\","
                    + "\"started_by\":\"user:000000000000000000000004\","
                    + "\"started_at\":\"2026-07-28T11:08:41.127Z\","
                    + "\"created_at\":\"2026-07-28T11:08:41.128Z\","
                    + "\"updated_at\":\"2026-07-29T10:59:13.166Z\"}";

    private static final String AGENT_TARGET =
            "{\"type\":\"agent\",\"interaction_ref\":\"sys:GeneralAgent\","
                    + "\"statuses\":[\"completed\",\"hibernating\",\"failed\"],"
                    + "\"missing_thread\":\"queue\"}";

    private static JsonObject object(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonElement member(String json, String name) {
        return object(json).get(name);
    }

    private static void assertWritesBack(String received, String written, String... names) {
        for (String name : names) {
            assertEquals(member(received, name), member(written, name), name);
        }
    }

    @Test
    void enumModelPropertyInAUnionVariant() {
        AgentRunResponse run = JSON.deserialize(RUN, AgentRunResponse.class);
        assertEquals(
                AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API,
                run.getAutonomousRunResponse().getStatus());
        assertEquals("\"hibernating\"", member(run.toJson(), "status").toString());
        assertEquals(
                "\"hibernating\"",
                member(run.getAutonomousRunResponse().toJson(), "status").toString());
    }

    @Test
    void inlineEnumProperty() {
        String body =
                "{\"id\":\"k\",\"name\":\"n\",\"account\":\"a\",\"enabled\":true,"
                        + "\"created_by\":\"u\",\"updated_by\":\"u\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\","
                        + "\"updated_at\":\"2026-01-01T00:00:00Z\","
                        + "\"scope\":\"organization\",\"profile\":\"future_profile\"}";
        AccountApiKey key = JSON.deserialize(body, AccountApiKey.class);
        assertEquals(AccountApiKey.ScopeEnum.UNKNOWN_DEFAULT_OPEN_API, key.getScope());
        assertEquals(AccountApiKey.ProfileEnum.UNKNOWN_DEFAULT_OPEN_API, key.getProfile());
        JsonObject written = object(key.toJson());
        assertEquals("organization", written.get("scope").getAsString());
        assertEquals("future_profile", written.get("profile").getAsString());
        assertEquals(written, object(JSON.serialize(key)));
    }

    @Test
    void enumArraysAndInlineEnumsInAVariant() {
        AgentEventDeliveryTarget target =
                JSON.deserialize(AGENT_TARGET, AgentEventDeliveryTarget.class);
        assertEquals(
                List.of(
                        AgentRunStatus.COMPLETED,
                        AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API,
                        AgentRunStatus.FAILED),
                target.getStatuses());
        assertEquals(
                AgentEventDeliveryTarget.MissingThreadEnum.UNKNOWN_DEFAULT_OPEN_API,
                target.getMissingThread());
        assertWritesBack(AGENT_TARGET, target.toJson(), "statuses", "missing_thread");

        // The same variant through its union.
        EventDeliveryTarget union = JSON.deserialize(AGENT_TARGET, EventDeliveryTarget.class);
        assertInstanceOf(AgentEventDeliveryTarget.class, union.getActualInstance());
        assertWritesBack(AGENT_TARGET, union.toJson(), "statuses", "missing_thread");
    }

    @Test
    void variantWrittenByItsReflectiveAdapter() {
        // This variant sorts before its union, so Gson decodes and writes it with its reflective
        // adapter rather than its own: the union keeps and restores its values.
        String body =
                "{\"integration\":\"ask_user_webhook\",\"enabled\":true,\"webhook_secret\":\"s\","
                        + "\"events\":[\"requested\",\"escalated\"]}";
        ProjectIntegrationConfigResponse response =
                JSON.deserialize(body, ProjectIntegrationConfigResponse.class);
        AskUserWebhookConfiguration webhook =
                assertInstanceOf(AskUserWebhookConfiguration.class, response.getActualInstance());
        assertEquals(
                List.of(
                        AskUserWebhookConfiguration.EventsEnum.REQUESTED,
                        AskUserWebhookConfiguration.EventsEnum.UNKNOWN_DEFAULT_OPEN_API),
                webhook.getEvents());
        assertWritesBack(body, response.toJson(), "integration", "events");
        assertWritesBack(body, webhook.toJson(), "integration", "events");
    }

    @Test
    void numericEnums() {
        String body = "{\"t\":99,\"m\":\"hello\",\"f\":7}";
        CompactMessage message = JSON.deserialize(body, CompactMessage.class);
        assertEquals(AgentMessageType.NUMBER_unknown_default_open_api, message.getT());
        assertEquals(CompactMessage.FEnum.NUMBER_unknown_default_open_api, message.getF());
        JsonObject written = object(message.toJson());
        assertEquals(99, written.get("t").getAsInt());
        assertEquals(7, written.get("f").getAsInt());
    }

    @Test
    void aChangedPropertyIsWrittenAsItNowIs() {
        AgentEventDeliveryTarget target =
                JSON.deserialize(AGENT_TARGET, AgentEventDeliveryTarget.class);
        target.missingThread(AgentEventDeliveryTarget.MissingThreadEnum.SKIP);
        assertEquals("skip", object(target.toJson()).get("missing_thread").getAsString());

        // An unchanged list keeps its unknown element beside a changed property.
        AgentEventDeliveryTarget other =
                JSON.deserialize(AGENT_TARGET, AgentEventDeliveryTarget.class);
        other.interactionRef("sys:OtherAgent");
        assertWritesBack(AGENT_TARGET, other.toJson(), "statuses");

        // A model built by the caller writes what it holds.
        assertEquals(
                "unknown_default_open_api",
                object(
                                new AgentEventDeliveryTarget()
                                        .missingThread(
                                                AgentEventDeliveryTarget.MissingThreadEnum
                                                        .UNKNOWN_DEFAULT_OPEN_API)
                                        .toJson())
                        .get("missing_thread")
                        .getAsString());
    }

    private static void assertStatusesAfter(Consumer<List<AgentRunStatus>> edit, String expected) {
        AgentEventDeliveryTarget target =
                JSON.deserialize(AGENT_TARGET, AgentEventDeliveryTarget.class);
        edit.accept(target.getStatuses());
        assertEquals(JsonParser.parseString(expected), object(target.toJson()).get("statuses"));
    }

    @Test
    void anEditedListKeepsItsUnknownValues() {
        // The list is ["completed","hibernating","failed"].
        assertStatusesAfter(
                statuses -> statuses.set(0, AgentRunStatus.RUNNING),
                "[\"running\",\"hibernating\",\"failed\"]");
        assertStatusesAfter(
                statuses -> statuses.add(AgentRunStatus.RUNNING),
                "[\"completed\",\"hibernating\",\"failed\",\"running\"]");
        assertStatusesAfter(statuses -> statuses.remove(0), "[\"hibernating\",\"failed\"]");
        assertStatusesAfter(
                statuses -> {
                    statuses.remove(0);
                    statuses.add(AgentRunStatus.RUNNING);
                },
                "[\"hibernating\",\"failed\",\"running\"]");
        assertStatusesAfter(Collections::reverse, "[\"failed\",\"hibernating\",\"completed\"]");
        assertStatusesAfter(statuses -> statuses.remove(1), "[\"completed\",\"failed\"]");
        // A placeholder the caller adds cannot be told apart from the received one.
        assertStatusesAfter(
                statuses -> statuses.add(AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API),
                "[\"completed\",\"unknown_default_open_api\",\"failed\","
                        + "\"unknown_default_open_api\"]");
        assertStatusesAfter(
                statuses -> statuses.set(0, AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API),
                "[\"unknown_default_open_api\",\"unknown_default_open_api\",\"failed\"]");
    }

    @Test
    void aReplacedListWritesWhatItHolds() {
        // The kept values belong to the list that was decoded, not to a list the caller sets.
        AgentEventDeliveryTarget target =
                JSON.deserialize(AGENT_TARGET, AgentEventDeliveryTarget.class);
        target.setStatuses(new ArrayList<>(List.of(AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API)));
        assertEquals(
                JsonParser.parseString("[\"unknown_default_open_api\"]"),
                object(target.toJson()).get("statuses"));
        target.setStatuses(
                new ArrayList<>(
                        List.of(
                                AgentRunStatus.COMPLETED,
                                AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API,
                                AgentRunStatus.FAILED)));
        assertEquals(
                JsonParser.parseString("[\"completed\",\"unknown_default_open_api\",\"failed\"]"),
                object(target.toJson()).get("statuses"));
    }

    @Test
    void unionWithAnEnumBranchTakesTheNextBranchForAnUnknownValue() {
        AuditAction known = JSON.deserialize("\"create\"", AuditAction.class);
        assertEquals(KnownAuditAction.CREATE, known.getActualInstance());

        AuditAction future = JSON.deserialize("\"future_action\"", AuditAction.class);
        assertEquals("future_action", future.getActualInstance());
        assertEquals("\"future_action\"", future.toJson());

        String filter = "{\"actions\":[\"create\",\"future_action\"]}";
        AuditAdoptionFilter decoded = JSON.deserialize(filter, AuditAdoptionFilter.class);
        assertWritesBack(filter, decoded.toJson(), "actions");
    }

    /** A map of enum lists, a shape no published model has yet. */
    static class EnumMapModel {
        @SerializedName("by_team")
        Map<String, List<AgentRunStatus>> byTeam;

        transient Object receivedEnumValues;
    }

    @Test
    void enumMaps() {
        String body = "{\"by_team\":{\"a\":[\"completed\"],\"b\":[\"hibernating\",\"failed\"]}}";
        EnumMapModel model =
                UnknownEnumValues.keep(
                        JsonParser.parseString(body),
                        JSON.getGson().fromJson(body, EnumMapModel.class));
        assertEquals(
                List.of(AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API, AgentRunStatus.FAILED),
                model.byTeam.get("b"));
        JsonElement tree = JSON.getGson().toJsonTree(model);
        UnknownEnumValues.restore(tree, model);
        assertEquals(JsonParser.parseString(body), tree);

        // Entries are kept by key.
        model.byTeam.remove("a");
        model.byTeam.put("c", List.of(AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API));
        tree = JSON.getGson().toJsonTree(model);
        UnknownEnumValues.restore(tree, model);
        assertEquals(
                JsonParser.parseString(
                        "{\"by_team\":{\"b\":[\"hibernating\",\"failed\"],"
                                + "\"c\":[\"unknown_default_open_api\"]}}"),
                tree);

        // A list or a map the caller puts in place of a decoded one writes what it holds.
        model.byTeam.put(
                "b",
                new ArrayList<>(
                        List.of(AgentRunStatus.UNKNOWN_DEFAULT_OPEN_API, AgentRunStatus.FAILED)));
        tree = JSON.getGson().toJsonTree(model);
        UnknownEnumValues.restore(tree, model);
        assertEquals(
                JsonParser.parseString(
                        "{\"by_team\":{\"b\":[\"unknown_default_open_api\",\"failed\"],"
                                + "\"c\":[\"unknown_default_open_api\"]}}"),
                tree);
        model =
                UnknownEnumValues.keep(
                        JsonParser.parseString(body),
                        JSON.getGson().fromJson(body, EnumMapModel.class));
        model.byTeam = new java.util.LinkedHashMap<>(model.byTeam);
        tree = JSON.getGson().toJsonTree(model);
        UnknownEnumValues.restore(tree, model);
        assertEquals(
                JsonParser.parseString(
                        "{\"by_team\":{\"a\":[\"completed\"],"
                                + "\"b\":[\"unknown_default_open_api\",\"failed\"]}}"),
                tree);
    }

    @Test
    void everyModelWithAnEnumPropertyCanKeepUnknownValues() throws Exception {
        Path models =
                Path.of(
                                AgentRunStatus.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .resolve("io/vertesia/model");
        List<String> missing = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.list(models)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".class") || name.contains("$")) {
                    continue;
                }
                Class<?> type =
                        Class.forName("io.vertesia.model." + name.substring(0, name.length() - 6));
                boolean holdsEnums = false;
                for (Field field : type.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    holdsEnums |=
                            !Modifier.isStatic(modifiers)
                                    && !Modifier.isTransient(modifiers)
                                    && UnknownEnumValues.holdsEnums(field.getGenericType());
                }
                if (!holdsEnums) {
                    continue;
                }
                checked++;
                try {
                    Field store = type.getDeclaredField(UnknownEnumValues.FIELD);
                    assertTrue(Modifier.isTransient(store.getModifiers()), type.getName());
                    assertEquals(Object.class, store.getType(), type.getName());
                } catch (NoSuchFieldException e) {
                    missing.add(type.getSimpleName());
                }
            }
        }
        assertTrue(checked > 100, "only " + checked + " models hold enums");
        assertEquals(List.of(), missing);
    }
}
