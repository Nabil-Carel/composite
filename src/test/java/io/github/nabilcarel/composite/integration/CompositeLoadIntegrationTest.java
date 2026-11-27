package io.github.nabilcarel.composite.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.integration.controllers.TestLimitsController;
import io.github.nabilcarel.composite.internal.ActiveBatches;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** A bounded concurrency check: every batch must succeed and leave no state behind. */
@SpringBootTest(
    classes = CompositeIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CompositeLoadIntegrationTest {

    private static final int CLIENTS = Integer.getInteger("composite.load.clients", 40);
    private static final int BATCHES_PER_CLIENT = 3;
    private static final int SUB_REQUESTS = 10;

    @LocalServerPort
    private int port;
    @Autowired
    private ActiveBatches activeBatches;

    @Test
    void concurrentBatches_allSucceed_andLeaveNoTrackers() throws Exception {
        TestLimitsController.reset();
        ObjectMapper json = new ObjectMapper();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String body = "{\"subRequests\":[" + IntStream.range(0, SUB_REQUESTS)
            .mapToObj(i -> "{\"referenceId\":\"w" + i + "\",\"method\":\"GET\",\"url\":\"/api/limits/work/" + i + "\"}")
            .collect(Collectors.joining(",")) + "]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/composite/execute"))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        ExecutorService executor = Executors.newFixedThreadPool(CLIENTS);
        long started = System.nanoTime();
        List<Future<List<String>>> clients = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            clients.add(executor.submit(() -> {
                List<String> failures = new ArrayList<>();
                for (int batch = 0; batch < BATCHES_PER_CLIENT; batch++) {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    JsonNode result = json.readTree(response.body());
                    if (response.statusCode() != 200 || result.path("hasErrors").asBoolean(true)) {
                        failures.add(response.statusCode() + " " + response.body());
                    }
                }
                return failures;
            }));
        }

        List<String> failures = new ArrayList<>();
        for (Future<List<String>> future : clients) {
            failures.addAll(future.get());
        }
        executor.shutdown();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.printf("LOAD batches=%d subRequests=%d failed=%d elapsedMs=%d maxActive=%d%n",
            CLIENTS * BATCHES_PER_CLIENT, CLIENTS * BATCHES_PER_CLIENT * SUB_REQUESTS, failures.size(),
            elapsedMillis, TestLimitsController.MAX_ACTIVE.get());

        assertThat(failures).as("first failure: %s", failures.stream().findFirst().orElse("none")).isEmpty();
        assertThat(TestLimitsController.COMPLETED).hasValue(CLIENTS * BATCHES_PER_CLIENT * SUB_REQUESTS);
        assertThat(activeBatches.count()).isZero();
    }
}
