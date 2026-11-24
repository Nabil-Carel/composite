package io.github.nabilcarel.composite.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.MediaType;

/**
 * Prototype dispatcher built on the JDK HTTP client, so sub-requests need no Netty or Reactor.
 *
 * <p>Like the default pool, it queues calls beyond its connection limit instead of sending them
 * to the handler limit. It is used only to compare clients and does not yet enforce a response
 * size limit while reading.
 */
final class JdkHttpSubRequestDispatcher implements SubRequestDispatcher {

    /** Headers the JDK client sets itself and refuses from callers. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private final Supplier<String> baseUrl;
    private final ObjectMapper objectMapper;
    private final AtomicInteger permits;
    private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();

    JdkHttpSubRequestDispatcher(Supplier<String> baseUrl, ObjectMapper objectMapper, int maxConnections) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.permits = new AtomicInteger(maxConnections);
    }

    @Override
    public CompletionStage<DispatchResult> dispatch(PreparedSubRequest request, CancellationSignal cancellation) {
        CompletableFuture<DispatchResult> result = new CompletableFuture<>();
        waiting.add(() -> send(request, cancellation, result));
        drain();
        return result;
    }

    private void drain() {
        while (!waiting.isEmpty()) {
            int available = permits.get();
            if (available == 0) {
                return;
            }
            if (!permits.compareAndSet(available, available - 1)) {
                continue;
            }
            Runnable next = waiting.poll();
            if (next == null) {
                release();
                return;
            }
            next.run();
        }
    }

    private void release() {
        permits.incrementAndGet();
        drain();
    }

    private void send(PreparedSubRequest request, CancellationSignal cancellation,
                      CompletableFuture<DispatchResult> result) {
        if (cancellation.isCancelled()) {
            release();
            return;
        }

        HttpRequest httpRequest;
        try {
            httpRequest = toHttpRequest(request);
        } catch (Exception e) {
            release();
            result.completeExceptionally(e);
            return;
        }

        CompletableFuture<HttpResponse<String>> call =
            client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        cancellation.onCancel(() -> call.cancel(true));
        call.whenComplete((response, failure) -> {
            release();
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            MediaType contentType = response.headers().firstValue("Content-Type")
                .map(MediaType::parseMediaType).orElse(null);
            String body = response.body() == null || response.body().isEmpty() ? null : response.body();
            result.complete(new DispatchResult(response.statusCode(), contentType, body));
        });
    }

    private HttpRequest toHttpRequest(PreparedSubRequest request) throws Exception {
        HttpRequest.BodyPublisher body = request.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(request.body()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.get() + request.url()))
            .method(request.method().name(), body);

        request.headers().forEach((name, values) -> {
            if (!RESTRICTED.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        if (request.body() != null && !request.headers().containsKey("Content-Type")) {
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        }
        return builder.build();
    }
}
