package io.github.nabilcarel.composite.model;

import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.Getter;

/**
 * The ResponseTracker class manages the tracking of sub-responses in a composite operation. It
 * maintains an ObservableInt to count outstanding responses and an ObservableMap to associate
 * response IDs with SubResponse objects. The class provides methods to add new responses, decrement
 * the response count, and perform cleanup. Designed for concurrent and event-driven scenarios, it
 * enables observers to react to changes in response state.
 */
public class ResponseTrackerImpl implements ResponseTracker {
  @Getter private final CompletableFuture<CompositeResponse> future = new CompletableFuture<>();
  private final AtomicInteger remainingResponses;
  @Getter private Map<String, SubResponse> subResponseMap = new ConcurrentHashMap<>();
  private Consumer<String> onSubRequestResolved;

  public ResponseTrackerImpl(int value) {
    remainingResponses = new AtomicInteger(value);
  }

  public void addResponse(String subRequestId, SubResponse subResponse) {
    subResponseMap.put(subRequestId, subResponse);
    int remaining = remainingResponses.decrementAndGet();

    if (onSubRequestResolved != null) {
      onSubRequestResolved.accept(subRequestId);
    }

    if (remaining == 0) {
      completeResponse();
    }
  }

  private void completeResponse() {
    future.complete(CompositeResponse.builder().responses(subResponseMap).build());
  }

  public void setOnSubRequestResolved(Consumer<String> callback) {
    this.onSubRequestResolved = callback;
  }

  public void cancel(Throwable t) {
    future.completeExceptionally(t);
  }
}
