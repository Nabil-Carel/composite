package io.github.nabilcarel.composite.model;

import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link ResponseTracker} implementation that uses atomic counters and a
 * {@link java.util.concurrent.ConcurrentHashMap ConcurrentHashMap} to safely collect
 * sub-responses from multiple concurrent reactor threads.
 *
 * <p>An instance is created once per composite request with the total number of expected
 * sub-responses. Each call to {@link #addResponse} decrements an
 * {@link java.util.concurrent.atomic.AtomicInteger AtomicInteger} counter. When the counter
 * reaches zero, {@link #completeResponse()} assembles the final
 * {@link io.github.nabilcarel.composite.model.response.CompositeResponse CompositeResponse}
 * and completes the internal {@link java.util.concurrent.CompletableFuture CompletableFuture}.
 *
 * <p>The callback registered via
 * {@link #setOnSubRequestResolved(java.util.function.Consumer setOnSubRequestResolved)} is
 * stored in an {@link java.util.concurrent.atomic.AtomicReference AtomicReference} to
 * allow it to be set after construction without additional synchronisation.
 *
 * @see ResponseTracker
 * @since 0.0.1
 */
@Slf4j
public class ResponseTrackerImpl implements ResponseTracker {
  @Getter
  private final CompletableFuture<CompositeResponse> future = new CompletableFuture<>();
  private final AtomicInteger remainingResponses;
  @Getter
  private final Map<String, SubResponse> subResponseMap = new ConcurrentHashMap<>();
  private final AtomicReference<Consumer<String>> onSubRequestResolved = new AtomicReference<>();

  public ResponseTrackerImpl(int value) {
    remainingResponses = new AtomicInteger(value);
  }

  public void addResponse(String subRequestId, SubResponse subResponse) {
    subResponseMap.put(subRequestId, subResponse);
    int remaining = remainingResponses.decrementAndGet();

    // Notify callback (capture reference to avoid race)
    Consumer<String> callback = onSubRequestResolved.get();
    if (callback != null) {
      try {
        callback.accept(subRequestId);
      } catch (Exception e) {
        log.error("Callback failed for subRequestId {}: {}", subRequestId, e.getMessage(), e);
      }
    }

    if (remaining == 0) {
      completeResponse();
    }
  }

  private void completeResponse() {
    boolean hasErrors = subResponseMap.values().stream()
        .anyMatch(r -> r.getHttpStatus() < HttpStatus.OK.value()
            || r.getHttpStatus() >= HttpStatus.MULTIPLE_CHOICES.value());
    future.complete(CompositeResponse.builder()
        .responses(subResponseMap)
        .hasErrors(hasErrors)
        .build());
  }

  public void setOnSubRequestResolved(Consumer<String> callback) {
    this.onSubRequestResolved.set(callback);
  }

  public void cancel(Throwable t) {
    future.completeExceptionally(t);
  }
}
