package io.github.nabilcarel.composite.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.springframework.http.MediaType;

/**
 * Default dispatcher, backed by the non-blocking JDK {@link HttpClient}.
 *
 * <p>No thread waits for a response: the client's selector thread watches connections and an
 * executor completes results. That executor uses virtual threads when the application does. Calls above the connection limit wait in a queue rather
 * than reaching the server's handler limit. Responses are read up to
 * {@code composite.loopback.max-response-size}.
 */
public final class JdkHttpSubRequestDispatcher implements SubRequestDispatcher, AutoCloseable {

    /** Headers the JDK client sets itself and refuses from callers. */
    private static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient client;
    private final Executor executor;
    private final Supplier<String> baseUrl;
    private final ObjectMapper objectMapper;
    private final Duration responseTimeout;
    private final long maxResponseBytes;
    private final AtomicInteger permits;
    private final Queue<Runnable> waiting = new ConcurrentLinkedQueue<>();

    /**
     * Creates the dispatcher.
     *
     * @param baseUrl the application's base URL, read on each call
     * @param objectMapper serialises request bodies
     * @param executor completes responses; shut down with the dispatcher if it is an executor service
     * @param connectTimeout connection timeout
     * @param responseTimeout time to wait for response headers, or {@code null} for none
     * @param maxResponseBytes largest response body read into memory
     * @param maxConnections calls sent at the same time
     * @param sslContext TLS settings, or {@code null} for the JVM default
     */
    public JdkHttpSubRequestDispatcher(Supplier<String> baseUrl, ObjectMapper objectMapper, Executor executor,
                                       Duration connectTimeout, Duration responseTimeout, long maxResponseBytes,
                                       int maxConnections, SSLContext sslContext) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .executor(executor);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }

        this.client = builder.build();
        this.executor = executor;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.responseTimeout = responseTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.permits = new AtomicInteger(maxConnections);
    }

    @Override
    public CompletionStage<DispatchResult> dispatch(PreparedSubRequest request, CancellationSignal cancellation) {
        CompletableFuture<DispatchResult> result = new CompletableFuture<>();
        waiting.add(() -> send(request, cancellation, result));
        drain();
        return result;
    }

    @Override
    public void close() {
        if (executor instanceof ExecutorService service) {
            service.shutdownNow();
        }
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
                permits.incrementAndGet();
                continue;
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

        CompletableFuture<HttpResponse<byte[]>> call;
        try {
            call = client.sendAsync(toHttpRequest(request),
                    info -> new LimitedBodySubscriber(maxResponseBytes,
                            info.headers().firstValueAsLong("Content-Length").orElse(-1)));
        } catch (Exception e) {
            release();
            result.completeExceptionally(e);
            return;
        }

        cancellation.onCancel(() -> call.cancel(true));
        call.whenComplete((response, failure) -> {
            release();
            if (failure != null) {
                result.completeExceptionally(failure);
            } else {
                result.complete(toResult(response));
            }
        });
    }

    private HttpRequest toHttpRequest(PreparedSubRequest request) throws Exception {
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(request.body()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl.get() + request.url()))
                .method(request.method().name(), body);
        if (responseTimeout != null) {
            builder.timeout(responseTimeout);
        }

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

    private static DispatchResult toResult(HttpResponse<byte[]> response) {
        MediaType contentType = response.headers().firstValue("Content-Type")
                .map(JdkHttpSubRequestDispatcher::parseMediaType)
                .orElse(null);
        byte[] bytes = response.body();
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        String body = bytes == null || bytes.length == 0 ? null : new String(bytes, charset);
        return new DispatchResult(response.statusCode(), contentType, body);
    }

    private static MediaType parseMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Collects a body and stops reading once it passes the limit. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final long limit;
        private final boolean declaredTooLarge;
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(long limit, long declaredLength) {
            this.limit = limit;
            this.declaredTooLarge = declaredLength > limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (declaredTooLarge) {
                fail();
            } else {
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + (long) item.remaining() > limit) {
                    fail();
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.write(bytes, 0, bytes.length);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.toByteArray());
        }

        private void fail() {
            subscription.cancel();
            body.completeExceptionally(new ResponseTooLargeException(limit));
        }
    }
}
