package io.github.nabilcarel.composite.model;

import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Tracks sub-responses in a composite operation.
 * Thread-safe: designed for concurrent access from multiple request threads.
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
    future.complete(CompositeResponse.builder().responses(subResponseMap).build());
  }

  public void setOnSubRequestResolved(Consumer<String> callback) {
    this.onSubRequestResolved.set(callback);
  }

  public void cancel(Throwable t) {
    future.completeExceptionally(t);
  }
}
