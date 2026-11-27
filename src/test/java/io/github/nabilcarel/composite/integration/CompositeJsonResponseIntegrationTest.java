package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestJsonController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Verifies that references operate on the JSON a client would actually receive. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CompositeJsonResponseIntegrationTest {

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @BeforeEach
    void reset() {
        TestJsonController.lastBody = null;
    }

    @Test
    void reference_usesJsonFieldName() throws Exception {
        execute(get("a", "/api/json/account"), capture("b", "{\"v\":\"${a.user_id}\"}"));

        assertThat(json.readTree(TestJsonController.lastBody).get("v").asText()).isEqualTo("u1");
    }

    @Test
    void reference_cannotUseJavaFieldName() throws Exception {
        JsonNode response = execute(get("a", "/api/json/account"), capture("b", "{\"v\":\"${a.userId}\"}"));

        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(400);
        assertThat(TestJsonController.lastBody).isNull();
    }

    @Test
    void reference_cannotReadJsonIgnoredField() throws Exception {
        JsonNode response = execute(get("a", "/api/json/account"), capture("b", "{\"v\":\"${a.secret}\"}"));

        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(400);
        assertThat(TestJsonController.lastBody).isNull();
    }

    @Test
    void response_isReturnedAsTheEndpointWroteIt() throws Exception {
        JsonNode response = execute(get("a", "/api/json/mismatch"));

        assertThat(response.get("hasErrors").asBoolean()).isFalse();
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        assertThat(response.at("/responses/a/body/user_id/x").asInt()).isEqualTo(1);
    }

    @Test
    void invalidJson_isAVisibleFailure_andBlocksDependents() throws Exception {
        JsonNode response = execute(get("a", "/api/json/invalid"), capture("b", "{\"v\":\"${a.id}\"}"));

        assertThat(response.get("hasErrors").asBoolean()).isTrue();
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(502);
        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(424);
        assertThat(TestJsonController.lastBody).isNull();
    }

    @Test
    void textBody_isKept_andCannotBeNavigated() throws Exception {
        JsonNode response = execute(get("a", "/api/json/text"), capture("b", "{\"v\":\"${a.field}\"}"));

        assertThat(response.at("/responses/a/body").asText()).isEqualTo("plain value");
        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(400);
    }

    @Test
    void numbers_keepTheirExactValue() throws Exception {
        execute(get("a", "/api/json/numbers"),
            capture("b", "{\"big\":\"${a.big}\",\"price\":\"${a.price}\",\"label\":\"p=${a.price},s=${a.small}\"}"));

        assertThat(TestJsonController.lastBody)
            .contains("12345678901234567890")
            .contains("\"price\":19.10")
            .contains("p=19.10,s=1000");
    }

    @Test
    void paths_supportIndexesAndQuotedKeys() throws Exception {
        execute(get("a", "/api/json/nested"),
            capture("b", "{\"name\":\"${a.items[0].name}\",\"host\":\"${a['db.host']}\"}"));

        JsonNode captured = json.readTree(TestJsonController.lastBody);
        assertThat(captured.get("name").asText()).isEqualTo("first");
        assertThat(captured.get("host").asText()).isEqualTo("localhost");
    }

    @Test
    void wholeValueReference_canCopyAnObject() throws Exception {
        execute(get("a", "/api/json/nested"), capture("b", "{\"copy\":\"${a.object}\"}"));

        assertThat(json.readTree(TestJsonController.lastBody).at("/copy/a").asInt()).isEqualTo(1);
    }

    @Test
    void objectInsideLargerText_isRejected() throws Exception {
        JsonNode response = execute(get("a", "/api/json/nested"), capture("b", "{\"v\":\"x ${a.object}\"}"));

        assertThat(response.at("/responses/b/httpStatus").asInt()).isEqualTo(400);
        assertThat(TestJsonController.lastBody).isNull();
    }

    @Test
    void emptyResponse_hasNoBody() throws Exception {
        JsonNode response = execute(get("a", "/api/json/empty"));

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(204);
        assertThat(response.at("/responses/a/body").isNull()).isTrue();
        assertThat(response.get("hasErrors").asBoolean()).isFalse();
    }

    @Test
    void oversizedResponse_isAVisibleFailure() throws Exception {
        JsonNode response = execute(get("a", "/api/json/large"));

        assertThat(response.get("hasErrors").asBoolean()).isTrue();
        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(503);
    }

    @Test
    void listedResponseHeaders_areReturned_andCookiesNeverAre() throws Exception {
        JsonNode response = execute(get("a", "/api/json/created"));

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(201);
        assertThat(response.at("/responses/a/headers/Location").asText()).isEqualTo("/api/json/items/42");
        assertThat(response.at("/responses/a/headers/ETag").asText()).isEqualTo("\"v1\"");
        assertThat(response.at("/responses/a/headers").size()).isEqualTo(2);
        assertThat(response.toString()).doesNotContain("secret").doesNotContain("hidden");
    }

    @Test
    void jsonErrorBody_keepsItsStructure() throws Exception {
        JsonNode response = execute(get("a", "/api/json/problem"));

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(422);
        assertThat(response.at("/responses/a/body/detail").asText()).isEqualTo("Quantity must be positive");
    }

    @Test
    void invalidJsonErrorBody_staysText() throws Exception {
        JsonNode response = execute(get("a", "/api/json/broken-error"));

        assertThat(response.at("/responses/a/httpStatus").asInt()).isEqualTo(400);
        assertThat(response.at("/responses/a/body").asText()).isEqualTo("{not json");
    }

    private static String get(String referenceId, String url) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"GET\",\"url\":\"" + url + "\"}";
    }

    private static String capture(String referenceId, String body) {
        return "{\"referenceId\":\"" + referenceId + "\",\"method\":\"POST\",\"url\":\"/api/json/capture\",\"body\":"
            + body + "}";
    }

    private JsonNode execute(String... subRequests) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"subRequests\":[" + String.join(",", subRequests) + "]}";
        return json.readTree(rest.postForEntity("/api/composite/execute",
            new HttpEntity<>(body, headers), String.class).getBody());
    }
}
