package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Verifies that loopback calls reach a server bound to a single address. {@code 127.0.0.2} is a
 * loopback address on Linux and Windows that {@code localhost} does not resolve to.
 */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.address=" + CompositeServerAddressIntegrationTest.ADDRESS
)
@EnabledIf("canBind")
class CompositeServerAddressIntegrationTest {

    static final String ADDRESS = "127.0.0.2";

    @LocalServerPort
    private int port;

    static boolean canBind() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName(ADDRESS))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void serverBoundToOneAddress_receivesLoopbackCalls() throws Exception {
        String body = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/users/1\"}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + ADDRESS + ":" + port + "/api/composite/execute"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        // Built for Java 17, where HttpClient is not closeable.
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = new ObjectMapper().readTree(response.body());

        assertThat(json.at("/responses/a/httpStatus").asInt()).isEqualTo(200);
        assertThat(json.get("hasErrors").asBoolean()).isFalse();
    }
}
