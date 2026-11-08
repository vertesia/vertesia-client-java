package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.vertesia.api.*;
import io.vertesia.auth.Authentication;
import io.vertesia.auth.HttpBearerAuth;
import io.vertesia.model.*;
import io.vertesia.model.AccountProjectsResponse;
import io.vertesia.model.ApiKey;
import io.vertesia.model.ApiKeyTypes;
import io.vertesia.model.ApiKeyWithValue;
import io.vertesia.model.CatalogInteractionRef;
import io.vertesia.model.Collection;
import io.vertesia.model.ComplexCollectionSearchQuery;
import io.vertesia.model.ComplexSearchPayload;
import io.vertesia.model.ComputeInteractionFacetPayload;
import io.vertesia.model.ComputePromptFacetPayload;
import io.vertesia.model.ComputedFacetResponse;
import io.vertesia.model.ContentObjectType;
import io.vertesia.model.ContentObjectTypeCatalogEntry;
import io.vertesia.model.CreateApiKeyPayload;
import io.vertesia.model.CreateCollectionPayload;
import io.vertesia.model.CreateContentObjectTypePayload;
import io.vertesia.model.DeleteByIdResult;
import io.vertesia.model.DeleteCountResult;
import io.vertesia.model.DeleteOperationResult;
import io.vertesia.model.EmbeddingsStatusResponse;
import io.vertesia.model.ExecutionEnvironment;
import io.vertesia.model.ExecutionRunStatus;
import io.vertesia.model.FacetSpec;
import io.vertesia.model.IndexingStatusResponse;
import io.vertesia.model.Interaction;
import io.vertesia.model.InteractionCreatePayload;
import io.vertesia.model.InteractionExecutionPayload;
import io.vertesia.model.InteractionExecutionResult;
import io.vertesia.model.InteractionPromptSegmentInput;
import io.vertesia.model.InteractionPromptSegmentInputTemplate;
import io.vertesia.model.InteractionRef;
import io.vertesia.model.InteractionStatus;
import io.vertesia.model.InteractionVisibility;
import io.vertesia.model.ObjectSearchResponse;
import io.vertesia.model.ProjectRef;
import io.vertesia.model.PromptRole;
import io.vertesia.model.PromptSegmentDefType;
import io.vertesia.model.PromptTemplate;
import io.vertesia.model.PromptTemplateCreatePayload;
import io.vertesia.model.PromptTemplateRef;
import io.vertesia.model.PromptTemplateUpdatePayload;
import io.vertesia.model.RenderPromptResponse;
import io.vertesia.model.ResolvedInteractionExecutionInfo;
import io.vertesia.model.SystemRoles;
import io.vertesia.model.TemplateType;
import io.vertesia.model.UpdateApiKeyPayload;
import io.vertesia.model.UpdateCollectionPayload;
import io.vertesia.model.User;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

@org.junit.jupiter.api.Timeout(value = 15, unit = TimeUnit.MINUTES)
class GeneratedContractLiveTest {

    @FunctionalInterface
    private interface ApiCall<T> {
        T run() throws ApiException;
    }

    // Studio: account, project, interactions. Zeno: content objects, agent runs.
    // Same bearer token is used for both downstream servers.
    private static ApiClient studioClient;
    private static ApiClient zenoClient;
    private static ApiClient stsClient;
    private static String createdCollectionId;
    private static String createdContentTypeId;
    private static final Queue<String> createdPromptIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdInteractionIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdApiKeyIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdObjectIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdDataStoreIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdTaskIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdOAuthClientIds = new ConcurrentLinkedQueue<>();
    private static final Queue<String> createdProcessDefinitionIds = new ConcurrentLinkedQueue<>();
    // Mirrors the TypeScript integration-test retry policy for live smoke-test API calls.
    private static final int RETRY_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 500L;
    private static final long RETRY_MAX_DELAY_MS = 8_000L;
    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(429, 502, 503, 504);
    private static final OkHttpClient HTTP =
            new OkHttpClient.Builder()
                    .addInterceptor(GeneratedContractLiveTest::retryingInterceptor)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(180, TimeUnit.SECONDS)
                    .build();
    private static final Set<Class<?>> SURFACE_SMOKE_APIS =
            Set.of(
                    AccessControlEntriesApi.class,
                    AccountsApi.class,
                    ApiKeysApi.class,
                    AppsApi.class,
                    AuditTrailApi.class,
                    BulkOperationsApi.class,
                    CollectionsApi.class,
                    CommandsApi.class,
                    ContentObjectTypesApi.class,
                    CostsApi.class,
                    DataApi.class,
                    EnvironmentsApi.class,
                    FilesApi.class,
                    InteractionRunsApi.class,
                    InteractionsApi.class,
                    OAuthClientsApi.class,
                    OAuthGrantsApi.class,
                    OAuthProvidersApi.class,
                    ObjectsApi.class,
                    ProcessesApi.class,
                    ProjectsApi.class,
                    PromptTemplatesApi.class,
                    RemoteMcpConnectionsApi.class,
                    RenderingApi.class,
                    RolesApi.class,
                    TasksApi.class,
                    TokenServiceApi.class,
                    UserGroupsApi.class,
                    UsersApi.class,
                    WorkflowDefinitionsApi.class,
                    WorkflowRunsApi.class);

    private long testStartedAtNanos;

    private static void log(String message) {
        System.out.printf("%s [openapi-java] %s%n", OffsetDateTime.now(), message);
    }

    private static Response retryingInterceptor(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        IOException lastException = null;

        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            Response response;
            try {
                response = chain.proceed(request);
            } catch (IOException e) {
                lastException = e;
                if (attempt >= RETRY_ATTEMPTS) {
                    throw e;
                }

                long delayMs = retryDelayMs(null, attempt);
                logRetry(request, attempt, delayMs, "connection error: " + e.getMessage());
                sleepBeforeRetry(delayMs);
                continue;
            }

            if (attempt >= RETRY_ATTEMPTS || !RETRYABLE_STATUS_CODES.contains(response.code())) {
                return response;
            }

            long delayMs = retryDelayMs(response, attempt);
            logRetry(request, attempt, delayMs, "HTTP " + response.code());
            response.close();
            sleepBeforeRetry(delayMs);
        }

        throw lastException != null
                ? lastException
                : new IOException("retry loop exhausted for " + request.url());
    }

    private static long retryDelayMs(Response response, int attempt) {
        Long retryAfterMs =
                parseRetryAfterMs(response == null ? null : response.header("Retry-After"));
        if (retryAfterMs != null) {
            return Math.min(RETRY_MAX_DELAY_MS, Math.max(0L, retryAfterMs));
        }

        long exponential =
                Math.min(
                        RETRY_MAX_DELAY_MS,
                        RETRY_BASE_DELAY_MS * (1L << Math.min(attempt - 1, 10)));
        long jitterFloor = Math.max(1L, exponential / 2L);
        return ThreadLocalRandom.current().nextLong(jitterFloor, exponential + 1L);
    }

    private static Long parseRetryAfterMs(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }

        String trimmed = retryAfter.trim();
        try {
            return TimeUnit.SECONDS.toMillis(Long.parseLong(trimmed));
        } catch (NumberFormatException ignored) {
            // Retry-After can also be an HTTP date.
        }

        try {
            long retryAtMs =
                    OffsetDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant()
                            .toEpochMilli();
            return retryAtMs - System.currentTimeMillis();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static void logRetry(Request request, int attempt, long delayMs, String reason) {
        log(
                "RETRY "
                        + request.method()
                        + " "
                        + request.url().encodedPath()
                        + " after "
                        + reason
                        + " (attempt "
                        + (attempt + 1)
                        + "/"
                        + RETRY_ATTEMPTS
                        + ", delay "
                        + delayMs
                        + " ms)");
    }

    private static void sleepBeforeRetry(long delayMs) throws IOException {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while retrying API request", e);
        }
    }

    @BeforeEach
    void logTestStart(TestInfo testInfo) {
        testStartedAtNanos = System.nanoTime();
        log("START " + testInfo.getDisplayName());
    }

    @AfterEach
    void logTestEnd(TestInfo testInfo) {
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - testStartedAtNanos);
        log("END " + testInfo.getDisplayName() + " (" + elapsedMs + " ms)");
    }

    // openapi-generator does not register an Authentication for type: openIdConnect,
    // but every operation references "OpenID" alongside "bearerAuth". Subclass to
    // register a no-op for "OpenID" so the auth lookup doesn't throw.
    private static ApiClient newApiClient() {
        return new ApiClient() {
            {
                Map<String, Authentication> mutable = new HashMap<>(this.authentications);
                mutable.put("OpenID", (q, h, c, p, m, u) -> {});
                this.authentications = Collections.unmodifiableMap(mutable);
            }
        };
    }

    private static ApiClient configuredClient(String basePath, String bearerToken) {
        ApiClient c = newApiClient();
        c.setBasePath(basePath);
        c.setHttpClient(HTTP);
        c.addDefaultHeader("x-api-version", VertesiaClient.DEFAULT_API_VERSION);
        ((HttpBearerAuth) c.getAuthentication("bearerAuth")).setBearerToken(bearerToken);
        return c;
    }

    @BeforeAll
    static void configureClients() {
        VertesiaClient client = VertesiaClientLiveTest.newLiveClient(HTTP);
        studioClient = client.studioClient;
        zenoClient = client.storeClient;
        stsClient = client.tokenClient;
    }

    private static String resolveProjectId() throws ApiException {
        String envId = VertesiaClientLiveTest.env("VERTESIA_PROJECT_ID", "");
        if (envId != null && !envId.isEmpty()) return envId;
        AccountProjectsResponse projects =
                new AccountsApi(studioClient).listAccountProjects().execute();
        assumeFalse(
                projects.getData() == null || projects.getData().isEmpty(),
                "no projects available on this account");
        ProjectRef first = projects.getData().get(0);
        assertNotNull(first.getId(), "first ProjectRef has no id");
        return first.getId();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static <T> T optionalApiCall(ApiCall<T> call, int... skippableStatusCodes)
            throws ApiException {
        try {
            return call.run();
        } catch (ApiException e) {
            for (int statusCode : skippableStatusCodes) {
                if (e.getCode() == statusCode) {
                    return null;
                }
            }
            throw e;
        }
    }

    private static <T> T optionalApiCall(ApiCall<T> call) throws ApiException {
        return optionalApiCall(call, 400, 403, 404, 409, 501);
    }

    private static String agentRunId(AgentRunResponse run) {
        Object actual = run.getActualInstance();
        if (actual instanceof AutonomousRunResponse) {
            return ((AutonomousRunResponse) actual).getId();
        }
        if (actual instanceof ProgrammaticRunResponse) {
            return ((ProgrammaticRunResponse) actual).getId();
        }
        if (actual instanceof SupervisedRunResponse) {
            return ((SupervisedRunResponse) actual).getId();
        }
        return null;
    }

    private static String defaultContentTypeId() throws ApiException {
        List<ContentObjectType> types =
                new ContentObjectTypesApi(zenoClient)
                        .listContentObjectTypes()
                        .name("Default")
                        .layout(true)
                        .schema(true)
                        .limit(BigDecimal.valueOf(10))
                        .execute();
        assumeFalse(types == null || types.isEmpty(), "no Default content object type available");
        assertNotNull(types.get(0).getId(), "Default content object type has no id");
        return types.get(0).getId();
    }

    private static Map<String, List<String>> projectContentSecurity() {
        Map<String, List<String>> security = new HashMap<>();
        security.put("content:read", Collections.singletonList("project:*"));
        security.put("content:write", Collections.singletonList("project:*"));
        security.put("content:delete", Collections.singletonList("project:*"));
        return security;
    }

    private static ProjectedContentObjectApiResponse getObjectOrNull(
            ObjectsApi objectsApi, String objectId) throws ApiException {
        try {
            return objectsApi.getObject(objectId).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private static List<String> objectLookupCandidates(
            ContentObjectApiResponse created, String objectName) throws ApiException {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (created.getId() != null) {
            candidates.add(created.getId());
        }
        if (created.getRevision() != null && created.getRevision().getRoot() != null) {
            candidates.add(created.getRevision().getRoot());
        }

        ObjectSearchResponse search =
                new ObjectsApi(zenoClient)
                        .searchObjects(
                                new ComplexSearchPayload()
                                        .limit(BigDecimal.valueOf(10))
                                        .query(new ComplexSearchQuery().name(objectName)))
                        .execute();
        if (search.getResults() != null) {
            for (ProjectedContentObjectApiResponse item : search.getResults()) {
                if (objectName.equals(item.getName()) && item.getId() != null) {
                    candidates.add(item.getId());
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private static ApiClient unauthenticatedClient(ApiClient sourceClient) {
        return configuredClient(sourceClient.getBasePath(), "invalid-openapi-smoke-token");
    }

    private static boolean isOperationMethod(Method method) {
        String name = method.getName();
        return Modifier.isPublic(method.getModifiers())
                && method.getDeclaringClass().getSimpleName().endsWith("Api")
                && !name.endsWith("Call")
                && !name.endsWith("Async")
                && !name.endsWith("WithHttpInfo")
                && !name.equals("getApiClient")
                && !name.equals("setApiClient")
                && !name.equals("getHostIndex")
                && !name.equals("setHostIndex")
                && !name.equals("getCustomBaseUrl")
                && !name.equals("setCustomBaseUrl");
    }

    private static Object defaultArgument(Class<?> type) throws Exception {
        if (type == String.class) return "000000000000000000000000";
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == Boolean.class || type == boolean.class) return Boolean.FALSE;
        if (type == Integer.class || type == int.class) return 1;
        if (type == Long.class || type == long.class) return 1L;
        if (type == Double.class || type == double.class) return 1D;
        if (type == Float.class || type == float.class) return 1F;
        if (type == List.class) return Collections.emptyList();
        if (type == Map.class) return Collections.emptyMap();
        if (type == OffsetDateTime.class) return OffsetDateTime.parse("1970-01-01T00:00:00Z");
        if (type == Object.class) return Collections.emptyMap();
        if (type.isEnum()) return type.getEnumConstants()[0];

        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object[] defaultArguments(Method method) throws Exception {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultArgument(types[i]);
        }
        return args;
    }

    /** Builds an operation's request with every optional parameter set. */
    private static Object buildRequest(Object api, Method factory) throws Exception {
        Object request = factory.invoke(api, defaultArguments(factory));
        for (Method setter : request.getClass().getDeclaredMethods()) {
            if (Modifier.isPublic(setter.getModifiers())
                    && setter.getReturnType() == request.getClass()) {
                setter.invoke(request, defaultArguments(setter));
            }
        }
        return request;
    }

    private static ApiClient clientFor(
            Class<?> apiClass,
            ApiClient invalidStudioClient,
            ApiClient invalidZenoClient,
            ApiClient invalidStsClient) {
        if (apiClass == TokenServiceApi.class) return invalidStsClient;
        if (apiClass == AccessControlEntriesApi.class
                || apiClass == AccountsApi.class
                || apiClass == ApiKeysApi.class
                || apiClass == AppsApi.class
                || apiClass == AuditTrailApi.class
                || apiClass == EnvironmentsApi.class
                || apiClass == InteractionRunsApi.class
                || apiClass == InteractionsApi.class
                || apiClass == OAuthClientsApi.class
                || apiClass == OAuthGrantsApi.class
                || apiClass == OAuthProvidersApi.class
                || apiClass == ProjectsApi.class
                || apiClass == PromptTemplatesApi.class
                || apiClass == RemoteMcpConnectionsApi.class
                || apiClass == RolesApi.class
                || apiClass == UserGroupsApi.class
                || apiClass == UsersApi.class) {
            return invalidStudioClient;
        }
        return invalidZenoClient;
    }

    @AfterAll
    static void cleanupCreatedResources() {
        if (studioClient == null) {
            return;
        }
        log(
                "cleanup starting: objects="
                        + createdObjectIds.size()
                        + ", dataStores="
                        + createdDataStoreIds.size()
                        + ", tasks="
                        + createdTaskIds.size()
                        + ", processDefinitions="
                        + createdProcessDefinitionIds.size()
                        + ", interactions="
                        + createdInteractionIds.size()
                        + ", prompts="
                        + createdPromptIds.size()
                        + ", apiKeys="
                        + createdApiKeyIds.size()
                        + ", oauthClients="
                        + createdOAuthClientIds.size());
        if (zenoClient != null) {
            ObjectsApi objectsApi = new ObjectsApi(zenoClient);
            String objectId;
            while ((objectId = createdObjectIds.poll()) != null) {
                try {
                    objectsApi.deleteObject(objectId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            DataApi dataApi = new DataApi(zenoClient);
            String dataStoreId;
            while ((dataStoreId = createdDataStoreIds.poll()) != null) {
                try {
                    dataApi.deleteDataStore(dataStoreId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            TasksApi tasksApi = new TasksApi(zenoClient);
            String taskId;
            while ((taskId = createdTaskIds.poll()) != null) {
                try {
                    tasksApi.deleteTask(taskId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            ProcessesApi processesApi = new ProcessesApi(zenoClient);
            String processId;
            while ((processId = createdProcessDefinitionIds.poll()) != null) {
                try {
                    processesApi.deleteProcessDefinition(processId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }
        }

        if (studioClient != null) {
            InteractionsApi interactionsApi = new InteractionsApi(studioClient);
            String interactionId;
            while ((interactionId = createdInteractionIds.poll()) != null) {
                try {
                    interactionsApi.deleteInteraction(interactionId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            PromptTemplatesApi promptTemplatesApi = new PromptTemplatesApi(studioClient);
            String promptId;
            while ((promptId = createdPromptIds.poll()) != null) {
                try {
                    promptTemplatesApi.deletePrompt(promptId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            ApiKeysApi apiKeysApi = new ApiKeysApi(studioClient);
            String keyId;
            while ((keyId = createdApiKeyIds.poll()) != null) {
                try {
                    apiKeysApi.deleteApiKey(keyId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }

            OAuthClientsApi oauthClientsApi = new OAuthClientsApi(studioClient);
            String oauthClientId;
            while ((oauthClientId = createdOAuthClientIds.poll()) != null) {
                try {
                    oauthClientsApi.deleteOAuthClient(oauthClientId).execute();
                } catch (Exception ignored) {
                    // Best-effort cleanup should not mask test failures.
                }
            }
        }

        if (zenoClient == null) return;
        if (createdCollectionId != null) {
            try {
                new CollectionsApi(zenoClient).deleteCollection(createdCollectionId).execute();
            } catch (Exception ignored) {
                // Best-effort cleanup should not mask test failures.
            }
        }
        if (createdContentTypeId != null) {
            try {
                new ContentObjectTypesApi(zenoClient)
                        .deleteContentObjectType(createdContentTypeId)
                        .execute();
            } catch (Exception ignored) {
                // Best-effort cleanup should not mask test failures.
            }
        }
        log("cleanup complete");
    }

    @Test
    void listAccountMembersAndRetrieveOne() throws ApiException {
        List<User> members = new AccountsApi(studioClient).listAccountMembers().execute();
        assertNotNull(members, "listAccountMembers returned null");
        assumeFalse(members.isEmpty(), "no account members available");

        User firstMember = members.get(0);
        assertNotNull(firstMember.getId(), "first account member has no id");

        User retrieved = new UsersApi(studioClient).getUser(firstMember.getId()).execute();
        assertNotNull(retrieved, "getUser returned null");
        assertEquals(firstMember.getId(), retrieved.getId(), "getUser returned a different user");
    }

    @Test
    void promptEndpoints() throws ApiException {
        PromptTemplatesApi promptsApi = new PromptTemplatesApi(studioClient);

        List<PromptTemplateRef> prompts =
                promptsApi.listPrompts().limit(BigDecimal.valueOf(10)).execute();
        assertNotNull(prompts, "listPrompts returned null");

        ComputedFacetResponse facets =
                promptsApi
                        .computePromptFacets(
                                new ComputePromptFacetPayload()
                                        .facets(
                                                Collections.singletonList(
                                                        new FacetSpec()
                                                                .name("role")
                                                                .field("role"))))
                        .execute();
        assertNotNull(facets, "computePromptFacets returned null");

        prompts.forEach(prompt -> assertNotNull(prompt.getId(), "prompt ref has no id"));
    }

    @Test
    void listApiKeys() throws ApiException {
        List<ApiKey> keys = new ApiKeysApi(studioClient).listApiKeys().level("project").execute();
        assertNotNull(keys, "listApiKeys returned null");
    }

    @Test
    void apiKeyCrud() throws ApiException {
        ApiKeysApi apiKeysApi = new ApiKeysApi(studioClient);
        ApiKeyWithValue created;
        try {
            // `enabled` is not part of the create payload: creation hardcodes it true.
            created =
                    apiKeysApi
                            .createApiKey(
                                    new CreateApiKeyPayload()
                                            .name(uniqueName("java-openapi-key"))
                                            .type(ApiKeyTypes.SK)
                                            .role(new AssignableRoleName(SystemRoles.READER)))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(created, "createApiKey returned null");
        assertNotNull(created.getId(), "createApiKey returned no id");
        createdApiKeyIds.add(created.getId());
        assertNotNull(created.getValue(), "createApiKey returned no one-time key value");

        ApiKey read = apiKeysApi.getApiKey(created.getId()).execute();
        assertNotNull(read, "getApiKey returned null");

        ApiKey updated =
                apiKeysApi
                        .updateApiKey(
                                created.getId(),
                                // `type` is creation-only, so the update payload does not declare
                                // it.
                                new UpdateApiKeyPayload()
                                        .name(uniqueName("java-openapi-key-updated"))
                                        .role(new AssignableRoleName(SystemRoles.READER))
                                        .enabled(true))
                        .execute();
        assertNotNull(updated, "updateApiKey returned null");
        assertEquals(created.getId(), updated.getId(), "updateApiKey returned a different key");

        DeleteOperationResult deleted = apiKeysApi.deleteApiKey(created.getId()).execute();
        assertNotNull(deleted, "deleteApiKey returned null");
        createdApiKeyIds.remove(created.getId());
    }

    @Test
    void oauthClientCrud() throws ApiException {
        OAuthClientsApi oauthClientsApi = new OAuthClientsApi(studioClient);
        String clientName = uniqueName("java-openapi-oauth-client");

        OAuthClientCreateResponse created;
        try {
            created =
                    oauthClientsApi
                            .createOAuthClient(
                                    new CreateOAuthClientPayload()
                                            .clientName(clientName)
                                            .clientType(OAuthClientType.PUBLIC)
                                            .redirectUris(
                                                    Collections.singletonList(
                                                            "http://localhost/openapi-java/callback"))
                                            .grantTypes(
                                                    Collections.singletonList(
                                                            OAuthGrantType.AUTHORIZATION_CODE))
                                            .responseTypes(
                                                    Collections.singletonList(
                                                            OAuthResponseType.CODE))
                                            .tokenEndpointAuthMethod(
                                                    OAuthTokenEndpointAuthMethod.NONE)
                                            .allowedScopes(Collections.singletonList("openid")))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(created, "createOAuthClient returned null");
        assertNotNull(created.getClientId(), "createOAuthClient returned no client_id");
        createdOAuthClientIds.add(created.getClientId());
        log("created OAuth client id=" + created.getClientId());
        assertEquals(clientName, created.getClientName(), "created OAuth client name mismatch");

        OAuthClient retrieved = oauthClientsApi.getOAuthClient(created.getClientId()).execute();
        assertEquals(
                created.getClientId(),
                retrieved.getClientId(),
                "getOAuthClient returned a different client");

        String updatedName = uniqueName("java-openapi-oauth-client-updated");
        OAuthClient updated =
                oauthClientsApi
                        .updateOAuthClient(
                                created.getClientId(),
                                new UpdateOAuthClientPayload()
                                        .clientName(updatedName)
                                        .redirectUris(
                                                Collections.singletonList(
                                                        "http://localhost/openapi-java/updated-callback"))
                                        .grantTypes(
                                                Collections.singletonList(
                                                        OAuthGrantType.AUTHORIZATION_CODE))
                                        .responseTypes(
                                                Collections.singletonList(OAuthResponseType.CODE))
                                        .tokenEndpointAuthMethod(OAuthTokenEndpointAuthMethod.NONE)
                                        .allowedScopes(Collections.singletonList("openid")))
                        .execute();
        assertEquals(
                created.getClientId(),
                updated.getClientId(),
                "updateOAuthClient returned a different client");
        assertEquals(
                updatedName,
                updated.getClientName(),
                "updateOAuthClient did not update client_name");

        List<OAuthClient> listed = oauthClientsApi.listOAuthClients().execute();
        assertTrue(
                listed.stream()
                        .anyMatch(client -> created.getClientId().equals(client.getClientId())),
                "listOAuthClients did not include the created client");

        SuccessResponse deleted =
                oauthClientsApi.deleteOAuthClient(created.getClientId()).execute();
        assertNotNull(deleted, "deleteOAuthClient returned null");
        createdOAuthClientIds.remove(created.getClientId());
    }

    @Test
    void listAndGetEnvironments() throws ApiException {
        List<ExecutionEnvironment> environments =
                new EnvironmentsApi(studioClient).listEnvironments().execute();
        assertNotNull(environments, "listEnvironments returned null");
        assumeFalse(environments.isEmpty(), "no environments available");

        ExecutionEnvironment first = environments.get(0);
        assertNotNull(first.getId(), "first environment has no id");

        ExecutionEnvironment retrieved =
                new EnvironmentsApi(studioClient).getEnvironment(first.getId()).execute();
        assertNotNull(retrieved, "getEnvironment returned null");
        assertEquals(
                first.getId(),
                retrieved.getId(),
                "getEnvironment returned a different environment");
    }

    @Test
    void dataStoreCrudAndSchemaRead() throws ApiException {
        DataApi dataApi = new DataApi(zenoClient);
        String storeName = uniqueName("java-openapi-data-store");

        DataStore created;
        try {
            created =
                    dataApi.createDataStore(
                                    new CreateDataStorePayload()
                                            .name(storeName)
                                            .description(
                                                    "Created by Java OpenAPI integration tests")
                                            .tags(
                                                    Arrays.asList(
                                                            "integration-test", "openapi-java")))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(created, "createDataStore returned null");
        assertNotNull(created.getId(), "createDataStore returned no id");
        createdDataStoreIds.add(created.getId());
        log("created data store id=" + created.getId());
        assertEquals(storeName, created.getName(), "created data store name mismatch");

        DataStore retrieved = dataApi.getDataStore(created.getId()).execute();
        assertEquals(created.getId(), retrieved.getId(), "getDataStore returned a different store");

        DataStoreSchemaResponse schema = dataApi.getDataStoreSchema(created.getId()).execute();
        assertNotNull(schema, "getDataStoreSchema returned null");
        assertNotNull(schema.getActualInstance(), "getDataStoreSchema returned no union instance");

        List<DataStoreItem> stores = dataApi.listDataStores().execute();
        assertTrue(
                stores.stream().anyMatch(store -> created.getId().equals(store.getId())),
                "listDataStores did not include the created store");

        DataStoreArchiveResult deleted = dataApi.deleteDataStore(created.getId()).execute();
        assertNotNull(deleted, "deleteDataStore returned null");
        createdDataStoreIds.remove(created.getId());
    }

    @Test
    void interactionDiscoveryEndpoints() throws ApiException {
        InteractionsApi interactionsApi = new InteractionsApi(studioClient);

        ComputedFacetResponse facets =
                interactionsApi
                        .computeInteractionFacets(
                                new ComputeInteractionFacetPayload()
                                        .facets(
                                                Collections.singletonList(
                                                        new FacetSpec()
                                                                .name("status")
                                                                .field("status"))))
                        .execute();
        assertNotNull(facets, "computeInteractionFacets returned null");

        List<CatalogInteractionRef> systemInteractions =
                interactionsApi.listSystemInteractions().execute();
        assertNotNull(systemInteractions, "listSystemInteractions returned null");

        ResolvedInteractionExecutionInfo resolved =
                interactionsApi.resolveInteraction("sys:GeneralAgent").execute();
        assertNotNull(resolved, "resolveInteraction returned null");
        assertNotNull(resolved.getResolved(), "resolveInteraction returned no resolved field");
    }

    @Test
    void promptInteractionLifecycleAndExecution() throws ApiException {
        PromptTemplatesApi promptsApi = new PromptTemplatesApi(studioClient);
        InteractionsApi interactionsApi = new InteractionsApi(studioClient);
        String promptName = uniqueName("java-openapi-prompt");
        String interactionName = uniqueName("java-openapi-interaction");

        PromptTemplate prompt;
        try {
            prompt =
                    promptsApi
                            .createPrompt(
                                    new PromptTemplateCreatePayload()
                                            .name(promptName)
                                            .role(PromptRole.USER)
                                            .contentType(TemplateType.JST)
                                            .content(
                                                    "Answer with a short greeting for <%= name %>."))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(prompt, "createPrompt returned null");
        assertNotNull(prompt.getId(), "createPrompt returned no id");
        createdPromptIds.add(prompt.getId());
        log("created prompt id=" + prompt.getId());

        PromptTemplate retrievedPrompt = promptsApi.getPrompt(prompt.getId()).execute();
        assertEquals(
                prompt.getId(), retrievedPrompt.getId(), "getPrompt returned a different prompt");

        PromptTemplate updatedPrompt =
                promptsApi
                        .updatePrompt(
                                prompt.getId(),
                                new PromptTemplateUpdatePayload()
                                        .description("Updated by Java OpenAPI integration tests"))
                        .execute();
        assertEquals(
                prompt.getId(), updatedPrompt.getId(), "updatePrompt returned a different prompt");

        RenderPromptResponse rendered =
                promptsApi
                        .renderPrompt(prompt.getId(), Collections.singletonMap("name", "Vertesia"))
                        .execute();
        assertNotNull(rendered, "renderPrompt returned null");
        assertNotNull(rendered.getRendered(), "renderPrompt returned no rendered output");

        InteractionPromptSegmentInput segment =
                new InteractionPromptSegmentInput()
                        .type(PromptSegmentDefType.TEMPLATE)
                        .template(new InteractionPromptSegmentInputTemplate(prompt.getId()));

        Interaction interaction =
                interactionsApi
                        .createInteraction(
                                new InteractionCreatePayload()
                                        .name(interactionName)
                                        .status(InteractionStatus.DRAFT)
                                        .visibility(InteractionVisibility.PRIVATE)
                                        .tags(Arrays.asList("integration-test", "openapi-java"))
                                        .prompts(Collections.singletonList(segment)))
                        .execute();
        assertNotNull(interaction, "createInteraction returned null");
        assertNotNull(interaction.getId(), "createInteraction returned no id");
        createdInteractionIds.add(interaction.getId());
        log("created interaction id=" + interaction.getId());

        Interaction retrievedInteraction =
                interactionsApi.getInteraction(interaction.getId()).execute();
        assertEquals(
                interaction.getId(),
                retrievedInteraction.getId(),
                "getInteraction returned a different interaction");

        List<InteractionRef> listed =
                interactionsApi
                        .listInteractions()
                        .name(interactionName)
                        .limit(BigDecimal.valueOf(10))
                        .execute();
        assertTrue(
                listed.stream().anyMatch(item -> interaction.getId().equals(item.getId())),
                "listInteractions did not include the created interaction");

        InteractionExecutionResult execution =
                interactionsApi
                        .executeInteraction(
                                interaction.getId(),
                                new InteractionExecutionPayload()
                                        .data(Collections.singletonMap("name", "Vertesia")))
                        .execute();
        assertNotNull(execution, "executeInteraction returned null");
        assertNotNull(execution.getStatus(), "executeInteraction returned no status");
        assertTrue(
                execution.getStatus() == ExecutionRunStatus.COMPLETED
                        || execution.getStatus() == ExecutionRunStatus.FAILED,
                "executeInteraction returned an unexpected status");

        DeleteByIdResult deletedInteraction =
                interactionsApi.deleteInteraction(interaction.getId()).execute();
        assertNotNull(deletedInteraction, "deleteInteraction returned null");
        createdInteractionIds.remove(interaction.getId());

        DeleteByIdResult deletedPrompt = promptsApi.deletePrompt(prompt.getId()).execute();
        assertNotNull(deletedPrompt, "deletePrompt returned null");
        createdPromptIds.remove(prompt.getId());
    }

    @Test
    void contentObjectTypeCrud() throws ApiException {
        String typeName = uniqueName("java-openapi-type");
        Map<String, Object> objectSchema = new HashMap<>();
        Map<String, Object> properties = new HashMap<>();
        properties.put("title", Collections.singletonMap("type", "string"));
        objectSchema.put("type", "object");
        objectSchema.put("properties", properties);
        objectSchema.put("required", Collections.singletonList("title"));

        CreateContentObjectTypePayload createPayload =
                new CreateContentObjectTypePayload()
                        .name(typeName)
                        .description("Created by Java OpenAPI integration tests")
                        .objectSchema(objectSchema)
                        .tags(Arrays.asList("integration-test", "openapi-java"));

        ContentObjectType created =
                new ContentObjectTypesApi(zenoClient)
                        .createContentObjectType(createPayload)
                        .execute();
        createdContentTypeId = created.getId();
        assertNotNull(createdContentTypeId, "createContentObjectType returned no id");
        assertEquals(typeName, created.getName(), "created content type name mismatch");

        ContentObjectType byId =
                new ContentObjectTypesApi(zenoClient)
                        .getContentObjectType(createdContentTypeId)
                        .execute();
        assertEquals(
                createdContentTypeId,
                byId.getId(),
                "getContentObjectType returned a different type");

        ContentObjectType byName =
                new ContentObjectTypesApi(zenoClient)
                        .getContentObjectTypeByName(typeName)
                        .execute();
        assertEquals(
                createdContentTypeId,
                byName.getId(),
                "getContentObjectTypeByName returned a different type");

        List<ContentObjectType> listed =
                new ContentObjectTypesApi(zenoClient)
                        .listContentObjectTypes()
                        .name(typeName)
                        .layout(true)
                        .schema(true)
                        .limit(BigDecimal.valueOf(10))
                        .execute();
        assertTrue(
                listed.stream().anyMatch(type -> createdContentTypeId.equals(type.getId())),
                "listContentObjectTypes did not include the created type");

        List<ContentObjectTypeCatalogEntry> catalog =
                new ContentObjectTypesApi(zenoClient)
                        .listContentObjectTypeCatalog()
                        .tag("integration-test")
                        .layout(false)
                        .schema(false)
                        .limit(BigDecimal.valueOf(10))
                        .execute();
        assertNotNull(catalog, "listContentObjectTypeCatalog returned null");

        DeleteCountResult deleted =
                new ContentObjectTypesApi(zenoClient)
                        .deleteContentObjectType(createdContentTypeId)
                        .execute();
        assertNotNull(deleted, "deleteContentObjectType returned null");
        createdContentTypeId = null;
    }

    @Test
    void collectionCrudAndSearch() throws ApiException {
        String collectionName = uniqueName("java-openapi-collection");
        CreateCollectionPayload payload =
                new CreateCollectionPayload()
                        .name(collectionName)
                        .dynamic(false)
                        .description("Created by Java OpenAPI integration tests")
                        .tags(Arrays.asList("integration-test", "openapi-java"));

        Collection created = new CollectionsApi(zenoClient).createCollection(payload).execute();
        createdCollectionId = created.getId();
        assertNotNull(createdCollectionId, "createCollection returned no id");
        assertEquals(collectionName, created.getName(), "created collection name mismatch");

        Collection byId =
                new CollectionsApi(zenoClient).getCollection(createdCollectionId).execute();
        assertEquals(
                createdCollectionId, byId.getId(), "getCollection returned a different collection");

        List<Collection> searchResults =
                new CollectionsApi(zenoClient)
                        .searchCollections(
                                new ComplexCollectionSearchQuery()
                                        .name(collectionName)
                                        .dynamic(false)
                                        .limit(BigDecimal.valueOf(10)))
                        .execute();
        assertTrue(
                searchResults.stream()
                        .anyMatch(collection -> createdCollectionId.equals(collection.getId())),
                "searchCollections did not include the created collection");

        ObjectSearchResponse members =
                new CollectionsApi(zenoClient)
                        .searchCollectionMembers(
                                createdCollectionId,
                                new ComplexSearchPayload().limit(BigDecimal.valueOf(10)))
                        .execute();
        assertNotNull(members, "searchCollectionMembers returned null");
        assertNotNull(members.getResults(), "searchCollectionMembers returned no results field");

        // Update takes its own payload, not the create one: every field is optional, so an edit
        // sends only what it changes.
        Collection updated =
                new CollectionsApi(zenoClient)
                        .updateCollection(
                                createdCollectionId,
                                new UpdateCollectionPayload()
                                        .description("Updated by Java OpenAPI integration tests"))
                        .execute();
        assertEquals(
                createdCollectionId,
                updated.getId(),
                "updateCollection returned a different collection");

        DeleteCountResult deleted =
                new CollectionsApi(zenoClient).deleteCollection(createdCollectionId).execute();
        assertNotNull(deleted, "deleteCollection returned null");
        createdCollectionId = null;
    }

    @Test
    void commandStatusResponses() throws ApiException {
        IndexingStatusResponse indexingStatus =
                optionalApiCall(
                        () -> new CommandsApi(zenoClient).getIndexingStatus().execute(), 403);
        if (indexingStatus == null) {
            return;
        }
        assertNotNull(indexingStatus, "getIndexingStatus returned null");
        assertNotNull(
                indexingStatus.getInfrastructureEnabled(),
                "indexing status missing infrastructure flag");

        EmbeddingsStatusResponse embeddingStatus =
                new CommandsApi(zenoClient).getEmbeddingCommandStatus("text").execute();
        assertNotNull(embeddingStatus, "getEmbeddingCommandStatus returned null");
        assertNotNull(embeddingStatus.getStatus(), "embedding status missing status field");
    }

    @Test
    void accountAuxiliaryEndpoints() throws ApiException {
        AccountsApi accountsApi = new AccountsApi(studioClient);

        optionalApiCall(() -> accountsApi.listUserInvites().execute(), 403);
        optionalApiCall(() -> accountsApi.listProjectInvites().execute(), 403);
        optionalApiCall(() -> accountsApi.listAccountInvites().execute(), 403);
        assertNotNull(
                accountsApi.getAccountOnboardingProgress().execute(),
                "getAccountOnboardingProgress returned null");
        optionalApiCall(() -> accountsApi.getStripeBillingStatus().execute());
    }

    @Test
    void accessControlRolesAndGroupsEndpoints() throws ApiException {
        AccessControlEntriesApi acesApi = new AccessControlEntriesApi(studioClient);

        assertNotNull(
                optionalApiCall(() -> acesApi.listProjectAccessControlEntries().execute()),
                "listProjectAccessControlEntries returned null");
        List<AccessControlEntry> entries =
                optionalApiCall(() -> acesApi.listAccessControlEntries().execute());
        assertNotNull(entries, "listAccessControlEntries returned null");
        if (!entries.isEmpty() && entries.get(0).getId() != null) {
            AccessControlEntry retrieved =
                    optionalApiCall(
                            () -> acesApi.getAccessControlEntry(entries.get(0).getId()).execute());
            assertNotNull(retrieved, "getAccessControlEntry returned null");
        }
        assertNotNull(new RolesApi(studioClient).listRoles().execute(), "listRoles returned null");

        UserGroupsApi groupsApi = new UserGroupsApi(studioClient);
        List<UserGroup> groups = optionalApiCall(() -> groupsApi.listUserGroups().execute());
        assertNotNull(groups, "listUserGroups returned null");
        if (!groups.isEmpty() && groups.get(0).getId() != null) {
            UserGroup group =
                    optionalApiCall(() -> groupsApi.getUserGroup(groups.get(0).getId()).execute());
            assertNotNull(group, "getUserGroup returned null");
            assertNotNull(
                    optionalApiCall(
                            () -> groupsApi.listUserGroupMembers(groups.get(0).getId()).execute()),
                    "listUserGroupMembers returned null");
        }
    }

    @Test
    void appsOauthAndRemoteMcpDiscoveryEndpoints() throws ApiException {
        AppsApi appsApi = new AppsApi(studioClient);

        List<AppManifest> apps = appsApi.listApps().execute();
        assertNotNull(apps, "listApps returned null");
        apps.forEach(app -> assertNotNull(app.getName(), "app manifest has no name"));
        assertNotNull(
                optionalApiCall(() -> appsApi.listAppInstallations().execute()),
                "listAppInstallations returned null");
        optionalApiCall(() -> appsApi.listAllAppInstallations().execute());
        List<AppInstallation> installationRefs =
                optionalApiCall(() -> appsApi.listAppInstallationRefs().execute());
        assertNotNull(installationRefs, "listAppInstallationRefs returned null");

        optionalApiCall(() -> new OAuthClientsApi(studioClient).listOAuthClients().execute());
        optionalApiCall(
                () ->
                        new OAuthGrantsApi(studioClient)
                                .listOAuthGrants()
                                .limit(BigDecimal.valueOf(10))
                                .execute());
        OAuthProvidersApi providersApi = new OAuthProvidersApi(studioClient);
        List<OAuthProvider> providers =
                optionalApiCall(() -> providersApi.listOAuthProviders().execute());
        if (providers != null && !providers.isEmpty() && providers.get(0).getId() != null) {
            OAuthProvider provider =
                    optionalApiCall(
                            () ->
                                    providersApi
                                            .getOAuthProvider(providers.get(0).getId())
                                            .execute());
            assertNotNull(provider, "getOAuthProvider returned null");
            optionalApiCall(
                    () -> providersApi.getOAuthProviderStatus(providers.get(0).getId()).execute());
        }

        if (installationRefs != null && !installationRefs.isEmpty()) {
            AppInstallation installation = installationRefs.get(0);
            if (installation.getId() != null) {
                optionalApiCall(
                        () ->
                                new RemoteMcpConnectionsApi(studioClient)
                                        .listRemoteMcpConnectionStatuses(installation.getId())
                                        .execute());
            }
        }
    }

    @Test
    void auditCostsDataFilesTasksAndWorkflowListEndpoints() throws ApiException {
        optionalApiCall(
                () ->
                        new AuditTrailApi(studioClient)
                                .listAuditTrailEvents()
                                .limit(BigDecimal.valueOf(10))
                                .execute());

        assertNotNull(
                optionalApiCall(() -> new CostsApi(zenoClient).getModelPrices().execute()),
                "getModelPrices returned null");
        assertNotNull(
                optionalApiCall(() -> new DataApi(zenoClient).listDataStores().execute()),
                "listDataStores returned null");
        assertNotNull(
                optionalApiCall(
                        () ->
                                new FilesApi(zenoClient)
                                        .listProjectFiles("openapi-java-tests/")
                                        .execute()),
                "listProjectFiles returned null");
        assertNotNull(
                optionalApiCall(
                        () ->
                                new TasksApi(zenoClient)
                                        .listTasks()
                                        .limit(BigDecimal.valueOf(10))
                                        .execute()),
                "listTasks returned null");

        List<WorkflowDefinitionRef> definitions =
                optionalApiCall(
                        () ->
                                new WorkflowDefinitionsApi(zenoClient)
                                        .listWorkflowDefinitions()
                                        .execute());
        assertNotNull(definitions, "listWorkflowDefinitions returned null");
        if (!definitions.isEmpty() && definitions.get(0).getId() != null) {
            assertNotNull(
                    optionalApiCall(
                            () ->
                                    new WorkflowDefinitionsApi(zenoClient)
                                            .getWorkflowDefinition(definitions.get(0).getId())
                                            .execute()),
                    "getWorkflowDefinition returned null");
        }

        WorkflowRunsApi workflowRunsApi = new WorkflowRunsApi(zenoClient);
        assertNotNull(
                optionalApiCall(
                        () ->
                                workflowRunsApi
                                        .listWorkflowRuns(
                                                new ListWorkflowRunsPayload()
                                                        .pageSize(BigDecimal.valueOf(10)))
                                        .execute()),
                "listWorkflowRuns returned null");
    }

    @Test
    void taskCrudLifecycle() throws ApiException {
        TasksApi tasksApi = new TasksApi(zenoClient);
        String taskTitle = uniqueName("java-openapi-task");

        Task created;
        try {
            created =
                    tasksApi.createTask(
                                    new CreateTaskPayload()
                                            .title(taskTitle)
                                            .description(
                                                    "Created by Java OpenAPI integration tests")
                                            .source(
                                                    new TaskSource()
                                                            .type(TaskSource.TypeEnum.PROCESS)
                                                            .runId("000000000000000000000000")
                                                            .node("java-openapi-test")))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(created, "createTask returned null");
        assertNotNull(created.getId(), "createTask returned no id");
        createdTaskIds.add(created.getId());
        log("created task id=" + created.getId());
        assertEquals(taskTitle, created.getTitle(), "created task title mismatch");

        Task retrieved = tasksApi.getTask(created.getId()).execute();
        assertEquals(created.getId(), retrieved.getId(), "getTask returned a different task");

        Task updated =
                tasksApi.updateTask(
                                created.getId(),
                                new UpdateTaskPayload()
                                        .title(taskTitle + "-updated")
                                        .status(DurableTaskStatus.CANCELLED))
                        .execute();
        assertEquals(created.getId(), updated.getId(), "updateTask returned a different task");
        assertEquals(
                DurableTaskStatus.CANCELLED,
                updated.getStatus(),
                "updateTask did not update status");

        List<Task> listed =
                tasksApi.listTasks()
                        .status(Collections.singletonList("cancelled"))
                        .limit(BigDecimal.valueOf(10))
                        .execute();
        assertTrue(
                listed.stream().anyMatch(task -> created.getId().equals(task.getId())),
                "listTasks did not include the updated task");

        DeleteCountResult deleted = tasksApi.deleteTask(created.getId()).execute();
        assertNotNull(deleted, "deleteTask returned null");
        createdTaskIds.remove(created.getId());
    }

    @Test
    void processProjectAndRunDiscoveryEndpoints() throws ApiException {
        String projectId = resolveProjectId();
        ProjectsApi projectsApi = new ProjectsApi(studioClient);

        assertNotNull(projectsApi.listProjects().execute(), "listProjects returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectIntegrations(projectId).execute()),
                "listProjectIntegrations returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectPlugins(projectId).execute()),
                "listProjectPlugins returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectTools(projectId).execute()),
                "listProjectTools returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectAppTypes(projectId).execute()),
                "listProjectAppTypes returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectAppTemplates(projectId).execute()),
                "listProjectAppTemplates returned null");
        assertNotNull(
                optionalApiCall(() -> projectsApi.listProjectAppProcesses(projectId).execute()),
                "listProjectAppProcesses returned null");
        optionalApiCall(() -> projectsApi.getProjectCompositeApp(projectId).execute());

        assertNotNull(
                optionalApiCall(
                        () ->
                                new ProcessesApi(zenoClient)
                                        .listProcessDefinitions()
                                        .limit(BigDecimal.valueOf(10))
                                        .execute()),
                "listProcessDefinitions returned null");

        InteractionRunsApi runsApi = new InteractionRunsApi(studioClient);
        // listRuns takes the eleven published RunListingFilters fields as positional query params.
        // Keep this as a surface smoke with only the page limit set.
        assertNotNull(runsApi.listRuns().limit(10).execute(), "listRuns returned null");
        assertNotNull(
                runsApi.searchRuns(
                                new RunSearchPayload()
                                        .limit(BigDecimal.valueOf(10))
                                        .query(
                                                new RunSearchQuery()
                                                        .name(
                                                                uniqueName(
                                                                        "missing-java-openapi-run"))))
                        .execute(),
                "searchRuns returned null");
    }

    @Test
    void processDefinitionCrudLifecycle() throws ApiException {
        ProcessesApi processesApi = new ProcessesApi(zenoClient);
        String processName = uniqueName("java_openapi_process").replace("-", "_");

        JSONSchema contextSchema =
                new JSONSchema()
                        .type("object")
                        .properties(new HashMap<>())
                        .required(Collections.emptyList());
        ProcessContextDefinition context =
                new ProcessContextDefinition().schema(contextSchema).initial(new HashMap<>());
        Map<String, NodeDefinition> nodes = new HashMap<>();
        nodes.put("done", new NodeDefinition().type(ProcessNodeType.FINAL).title("Done"));
        ProcessDefinitionBody definition =
                new ProcessDefinitionBody()
                        .formatVersion(ProcessDefinitionFormatVersion.NUMBER_1)
                        .process(processName)
                        .initial("done")
                        .context(context)
                        .nodes(nodes);

        ProcessDefinition created;
        try {
            created =
                    processesApi
                            .createProcessDefinition(
                                    new CreateProcessDefinitionPayload()
                                            .name(processName)
                                            .description(
                                                    "Created by Java OpenAPI integration tests")
                                            .tags(Arrays.asList("integration-test", "openapi-java"))
                                            .definition(definition))
                            .execute();
        } catch (ApiException e) {
            if (e.getCode() == 403) {
                return;
            }
            throw e;
        }

        assertNotNull(created, "createProcessDefinition returned null");
        assertNotNull(created.getId(), "createProcessDefinition returned no id");
        createdProcessDefinitionIds.add(created.getId());
        log("created process definition id=" + created.getId());

        ProcessDefinition retrieved = processesApi.getProcessDefinition(created.getId()).execute();
        assertEquals(
                created.getId(),
                retrieved.getId(),
                "getProcessDefinition returned a different definition");

        ProcessDefinition updated =
                processesApi
                        .updateProcessDefinition(
                                created.getId(),
                                new UpdateProcessDefinitionPayload()
                                        .description("Updated by Java OpenAPI integration tests"))
                        .execute();
        assertEquals(
                created.getId(),
                updated.getId(),
                "updateProcessDefinition returned a different definition");

        List<ProcessDefinition> listed =
                processesApi
                        .listProcessDefinitions()
                        .process(processName)
                        .limit(BigDecimal.valueOf(10))
                        .allVersions(true)
                        .execute();
        assertTrue(
                listed.stream().anyMatch(process -> created.getId().equals(process.getId())),
                "listProcessDefinitions did not include the created definition");

        DeleteCountResult deleted = processesApi.deleteProcessDefinition(created.getId()).execute();
        assertNotNull(deleted, "deleteProcessDefinition returned null");
        createdProcessDefinitionIds.remove(created.getId());
    }

    @Test
    void generatedClientOperationSurfaceSmokeExceptAgentRuns() throws Exception {
        ApiClient invalidStudioClient = unauthenticatedClient(studioClient);
        ApiClient invalidZenoClient = unauthenticatedClient(zenoClient);
        ApiClient invalidStsClient = unauthenticatedClient(stsClient);

        int attempted = 0;
        List<String> unexpectedFailures = new ArrayList<>();

        for (Class<?> apiClass : SURFACE_SMOKE_APIS) {
            ApiClient client =
                    clientFor(apiClass, invalidStudioClient, invalidZenoClient, invalidStsClient);
            Object api = apiClass.getConstructor(ApiClient.class).newInstance(client);
            try {
                apiClass.getMethod("setCustomBaseUrl", String.class)
                        .invoke(api, client.getBasePath());
            } catch (NoSuchMethodException ignored) {
                // Older generated API classes may not expose custom base URL overrides.
            }
            for (Method method : apiClass.getDeclaredMethods()) {
                if (!isOperationMethod(method)) continue;
                attempted++;
                try {
                    Object request = buildRequest(api, method);
                    request.getClass().getMethod("execute").invoke(request);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof ApiException) {
                        int code = ((ApiException) cause).getCode();
                        if (code == 0) {
                            unexpectedFailures.add(
                                    apiClass.getSimpleName()
                                            + "."
                                            + method.getName()
                                            + " network failure: "
                                            + cause.getMessage());
                        }
                    } else {
                        unexpectedFailures.add(
                                apiClass.getSimpleName()
                                        + "."
                                        + method.getName()
                                        + " failed before receiving an API error: "
                                        + cause);
                    }
                } catch (Exception e) {
                    unexpectedFailures.add(
                            apiClass.getSimpleName()
                                    + "."
                                    + method.getName()
                                    + " could not be invoked: "
                                    + e);
                }
            }
        }

        assertTrue(unexpectedFailures.isEmpty(), String.join("\n", unexpectedFailures));
        assertTrue(
                attempted >= 300,
                "expected broad non-AgentRuns operation coverage, invoked " + attempted);
    }

    @Test
    void renderingAndBulkOperationRequestShapes() throws ApiException, IOException {
        BulkOperationPayload payload =
                BulkOperationPayload.fromJson("{\"name\":\"update\",\"ids\":[],\"params\":{}}");
        BulkOperationResponse bulk =
                optionalApiCall(
                        () ->
                                new BulkOperationsApi(zenoClient)
                                        .runBulkContentOperation(payload)
                                        .execute());
        if (bulk != null) {
            assertNotNull(
                    bulk.getActualInstance(), "runBulkContentOperation returned no union instance");
        }

        optionalApiCall(
                () ->
                        new RenderingApi(zenoClient)
                                .getRenderingJobStatus("missing-workflow-id", "missing-run-id")
                                .execute());
    }
}
