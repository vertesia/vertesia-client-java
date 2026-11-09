package io.vertesia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import io.vertesia.api.AccountsApi;
import io.vertesia.api.AppsApi;
import io.vertesia.api.ProcessesApi;
import java.io.IOException;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.Test;

/** Pins the generated request-builder shape without a server: {@code buildCall} builds the request but never sends it. */
class RequestBuilderTest {

    private static ApiClient client() {
        ApiClient client = new ApiClient();
        client.setBasePath("http://localhost");
        client.addDefaultHeader("x-api-version", "20260803");
        return client;
    }

    @Test
    void optionalParameterIsSentOnlyWhenSet() throws ApiException {
        AppsApi apps = new AppsApi(client());

        Request preview = apps.deleteApp("app-id").buildCall(null).request();
        assertNull(preview.url().queryParameter("confirm"));

        Request confirmed = apps.deleteApp("app-id").confirm(true).buildCall(null).request();
        assertEquals("true", confirmed.url().queryParameter("confirm"));
    }

    @Test
    void apiVersionComesFromTheDefaultHeaderNotAnArgument() throws ApiException {
        Request request = new AccountsApi(client()).getCurrentAccount().buildCall(null).request();

        assertEquals("20260803", request.header("x-api-version"));
    }

    @Test
    void finalProcessNodeOmitsAgentOnlyFields() throws ApiException, IOException {
        Request request =
                new ProcessesApi(client())
                        .createProcessDefinition(
                                GeneratedContractLiveTest.finalProcessDefinition("test"))
                        .buildCall(null)
                        .request();
        Buffer body = new Buffer();
        request.body().writeTo(body);
        var node =
                JsonParser.parseString(body.readUtf8())
                        .getAsJsonObject()
                        .getAsJsonObject("definition")
                        .getAsJsonObject("nodes")
                        .getAsJsonObject("done");

        assertEquals("final", node.get("type").getAsString());
        assertFalse(node.has("initial_skills"));
        assertFalse(node.has("excluded_tools"));
    }
}
