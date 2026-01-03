package io.github.nabilcarel.composite.model;

import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface ResponseTracker {
    Map<String, SubResponse> getSubResponseMap();
    CompletableFuture<CompositeResponse> getFuture();
    void addResponse(String subRequestId, SubResponse subResponse);
    void setOnSubRequestResolved(Consumer<String> callback);
    void cancel(Throwable t);
}
