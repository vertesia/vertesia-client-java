package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.cdimascio.dotenv.Dotenv;
import io.vertesia.model.BulkOperationPayload;
import io.vertesia.model.ComplexSearchPayload;
import io.vertesia.model.ContentObjectStatus;
import io.vertesia.model.ContentSource;
import io.vertesia.model.CreateContentObjectPayload;
import io.vertesia.model.CreateDataStorePayload;
import io.vertesia.model.GetUploadUrlPayload;
import io.vertesia.model.InteractionCreatePayload;
import io.vertesia.model.InteractionExecutionPayload;
import io.vertesia.model.InteractionPromptSegmentInput;
import io.vertesia.model.InteractionPromptSegmentInputTemplate;
import io.vertesia.model.InteractionStatus;
import io.vertesia.model.InteractionVisibility;
import io.vertesia.model.PromptRole;
import io.vertesia.model.PromptSegmentDefType;
import io.vertesia.model.PromptTemplateCreatePayload;
import io.vertesia.model.TemplateType;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

/** Live scenarios shared with the Go and Python SDK suites. */
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class VertesiaClientLiveTest {
    private static final Map<String, String> DOTENV = new HashMap<>();
    private static VertesiaClient client;
    private final List<Executable> cleanup = new ArrayList<>();

    @BeforeAll
    static void configureClient() {
        var dotenv = Dotenv.configure().ignoreIfMissing().ignoreIfMalformed().load();
        for (var entry : dotenv.entries()) {
            DOTENV.put(entry.getKey(), entry.getValue());
        }
        assumeTrue(
                "1".equals(env("VERTESIA_LIVE_TESTS", "0")),
                "live SDK tests are disabled; set VERTESIA_LIVE_TESTS=1 to run them");
        String apiKey = env("VERTESIA_API_KEY", "");
        assertTrue(
                apiKey.startsWith("sk-") && apiKey.length() > 3,
                "VERTESIA_LIVE_TESTS=1 requires VERTESIA_API_KEY to be an sk- secret key");
        client =
                new VertesiaClient(
                        new ClientOptions()
                                .setServerUrl(
                                        env("VERTESIA_STUDIO_URL", "http://localhost:8091/api/v1"))
                                .setStoreUrl(
                                        env("VERTESIA_ZENO_URL", "http://localhost:8092/api/v1"))
                                .setTokenServerUrl(env("VERTESIA_STS_URL", "http://localhost:8093"))
                                .setApiKey(apiKey));
    }

    @AfterEach
    void cleanUpResources() {
        // Run every deletion even if a prior assertion or deletion failed. Reverse creation order
        // removes interactions before their prompts. These callbacks only target this test's IDs.
        assertAll("delete live test resources", cleanup.stream());
    }

    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws ApiException;
    }

    private void onCleanup(ApiCall<?> deletion) {
        cleanup.add(
                0,
                () -> {
                    try {
                        deletion.run();
                    } catch (ApiException e) {
                        if (e.getCode() != 404) {
                            throw e;
                        }
                    }
                });
    }

    private static <T> T permittedCreation(ApiCall<T> creation) throws ApiException {
        try {
            return creation.run();
        } catch (ApiException e) {
            assumeTrue(e.getCode() != 403, "creation is not permitted for this test principal");
            throw e;
        }
    }

    @Test
    void readEndpoints() throws ApiException {
        assertNotNull(client.accounts.getCurrentAccount());
        String projectId = env("VERTESIA_PROJECT_ID", "");
        if (projectId.isEmpty()) {
            var projects = client.accounts.listAccountProjects();
            assertNotNull(projects.getData());
            assertFalse(projects.getData().isEmpty(), "expected at least one visible project");
            projectId = projects.getData().get(0).getId();
        }
        assertNotNull(client.projects.getProject(projectId));
        assertNotNull(
                client.promptTemplates.listPrompts(
                        null, null, BigDecimal.TEN, null, null, null, null));
        assertNotNull(
                client.interactions.listInteractions(
                        null,
                        null,
                        BigDecimal.TEN,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        assertNotNull(client.data.listDataStores());
        assertNotNull(client.workflowDefinitions.listWorkflowDefinitions());
        assertNotNull(
                client.objects
                        .searchObjects(new ComplexSearchPayload().limit(BigDecimal.TEN))
                        .getResults());
    }

    @Test
    void promptInteractionLifecycle() throws ApiException {
        var prompt =
                permittedCreation(
                        () ->
                                client.promptTemplates.createPrompt(
                                        new PromptTemplateCreatePayload()
                                                .name(uniqueName("java-openapi-prompt"))
                                                .role(PromptRole.USER)
                                                .content(
                                                        "Answer with a short greeting for {{name}}.")
                                                .contentType(TemplateType.HANDLEBARS)));
        assertNotNull(prompt.getId());
        onCleanup(() -> client.promptTemplates.deletePrompt(prompt.getId()));
        var rendered =
                client.promptTemplates.renderPrompt(prompt.getId(), Map.of("name", "Vertesia"));
        assertNotNull(rendered.getRendered());
        assertFalse(rendered.getRendered().isEmpty());

        var interaction =
                client.interactions.createInteraction(
                        new InteractionCreatePayload()
                                .name(uniqueName("java-openapi-interaction"))
                                .status(InteractionStatus.DRAFT)
                                .visibility(InteractionVisibility.PRIVATE)
                                .tags(List.of("integration-test", "openapi-java"))
                                .prompts(
                                        List.of(
                                                new InteractionPromptSegmentInput()
                                                        .type(PromptSegmentDefType.TEMPLATE)
                                                        .template(
                                                                new InteractionPromptSegmentInputTemplate(
                                                                        prompt.getId())))));
        assertNotNull(interaction.getId());
        onCleanup(() -> client.interactions.deleteInteraction(interaction.getId()));
        var result =
                client.interactions.executeInteraction(
                        interaction.getId(),
                        new InteractionExecutionPayload().data(Map.of("name", "Vertesia")));
        assertNotNull(result);
        assertNotNull(result.getStatus());
    }

    @Test
    void dataStoreCrud() throws ApiException {
        var created =
                permittedCreation(
                        () ->
                                client.data.createDataStore(
                                        new CreateDataStorePayload()
                                                .name(uniqueName("java-openapi-data-store"))
                                                .description(
                                                        "Created by Java OpenAPI integration tests")
                                                .tags(
                                                        List.of(
                                                                "integration-test",
                                                                "openapi-java"))));
        assertNotNull(created.getId());
        onCleanup(() -> client.data.deleteDataStore(created.getId()));
        assertEquals(created.getId(), client.data.getDataStore(created.getId()).getId());
        assertNotNull(client.data.getDataStoreSchema(created.getId()));
    }

    @Test
    void imageIntake() throws Exception {
        String typeId = env("VERTESIA_CONTENT_OBJECT_TYPE_ID", "");
        if (typeId.isEmpty()) {
            var types =
                    client.contentObjectTypes.listContentObjectTypes(
                            "Default", null, true, true, BigDecimal.TEN, null);
            assertFalse(types.isEmpty(), "image intake requires a Default content object type");
            typeId = types.get(0).getId();
        }
        String fileName = uniqueName("java-openapi-image") + ".jpg";
        var upload =
                client.files.getFileUploadUrl(
                        new GetUploadUrlPayload().name(fileName).mimeType("image/jpeg"));
        assertNotNull(upload.getId());
        assertNotNull(upload.getUrl());
        var bytes = new ByteArrayOutputStream();
        assertTrue(
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "jpg", bytes));
        // Signed uploads use a separate HTTP client so SDK bearer credentials are never forwarded.
        var http = new OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build();
        var request =
                new Request.Builder()
                        .url(upload.getUrl())
                        .put(RequestBody.create(bytes.toByteArray(), MediaType.parse("image/jpeg")))
                        .build();
        try (var response = http.newCall(request).execute()) {
            assertTrue(response.isSuccessful(), "signed upload failed: HTTP " + response.code());
        }
        var created =
                client.objects.createObject(
                        new CreateContentObjectPayload()
                                .type(typeId)
                                .name(fileName)
                                .content(
                                        new ContentSource()
                                                .source(upload.getId())
                                                .type("image/jpeg")
                                                .name(fileName))
                                .security(
                                        Map.of(
                                                "content:read", List.of("project:*"),
                                                "content:write", List.of("project:*"),
                                                "content:delete", List.of("project:*")))
                                .tags(List.of("integration-test", "openapi-java", "file-intake"))
                                .properties(Map.of("test_type", "openapi-java-image-intake")),
                        null,
                        null,
                        null,
                        null);
        assertNotNull(created.getId());
        onCleanup(() -> client.objects.deleteObject(created.getId()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (System.nanoTime() < deadline) {
            var latest = client.objects.getObject(created.getId());
            if (latest.getStatus() == ContentObjectStatus.COMPLETED
                    || latest.getStatus() == ContentObjectStatus.READY) {
                var source = client.objects.getObjectContentSource(created.getId());
                assertNotNull(source.getSource());
                assertFalse(source.getSource().isEmpty());
                return;
            }
            assertTrue(latest.getStatus() != ContentObjectStatus.FAILED, "object intake failed");
            Thread.sleep(3_000);
        }
        fail("timed out waiting for object intake");
    }

    @Test
    void requestShapes() throws ApiException {
        try {
            var response =
                    client.bulkOperations.runBulkContentOperationWithHttpInfo(
                            new BulkOperationPayload()
                                    .name(BulkOperationPayload.NameEnum.UPDATE)
                                    .ids(List.of())
                                    .params(Map.of()));
            assertEquals(200, response.getStatusCode());
        } catch (ApiException e) {
            assertTrue(
                    Set.of(400, 403, 404, 409).contains(e.getCode()),
                    "unexpected bulk operation HTTP status: " + e.getCode());
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = DOTENV.get(name);
        }
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
