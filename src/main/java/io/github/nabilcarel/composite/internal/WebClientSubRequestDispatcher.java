package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/** Default dispatcher, backed by a non-blocking WebClient. */
public final class WebClientSubRequestDispatcher implements SubRequestDispatcher {

    private final WebClient webClient;

    public WebClientSubRequestDispatcher(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public CompletionStage<DispatchResult> dispatch(PreparedSubRequest request, CancellationSignal cancellation) {
        WebClient.RequestBodySpec spec = webClient.method(request.method())
                .uri(request.url())
                .headers(headers -> headers.addAll(request.headers()));
        WebClient.RequestHeadersSpec<?> exchange = request.body() == null ? spec : spec.bodyValue(request.body());

        CompletableFuture<DispatchResult> result = new CompletableFuture<>();
        Disposable call = exchange.exchangeToMono(this::toResult)
                .subscribe(result::complete, result::completeExceptionally);
        cancellation.onCancel(call::dispose);
        return result;
    }

    private Mono<DispatchResult> toResult(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        HttpHeaders headers = response.headers().asHttpHeaders();
        MediaType contentType = response.headers().contentType().orElse(null);
        DispatchResult empty = new DispatchResult(status.value(), contentType, null, headers);

        if (status.is2xxSuccessful() || status.isError()) {
            return response.bodyToMono(String.class)
                    .map(text -> new DispatchResult(status.value(), contentType, text, headers))
                    .defaultIfEmpty(empty);
        }

        return response.releaseBody().thenReturn(empty);
    }
}
