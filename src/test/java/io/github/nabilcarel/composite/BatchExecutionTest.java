package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nabilcarel.composite.internal.BatchExecution;
import io.github.nabilcarel.composite.internal.SubRequestForwarder;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class BatchExecutionTest {

    private static final SubRequestContext CONTEXT = new SubRequestContext(new HttpHeaders(), null);

    /** Records dispatch order and leaves each call running until the test finishes it. */
    private static final class Calls implements SubRequestForwarder {
        private final List<String> started = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Sinks.Empty<Void>> running = new ConcurrentHashMap<>();
        private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
        private final Set<String> failing = ConcurrentHashMap.newKeySet();

        @Override
        public Mono<Void> forwardSubrequest(SubRequest subRequest, BatchExecution batch) {
            String id = subRequest.getReferenceId();
            started.add(id);
            if (failing.contains(id)) {
                throw new IllegalStateException("boom");
            }
            Sinks.Empty<Void> call = Sinks.empty();
            running.put(id, call);
            return call.asMono().doOnCancel(() -> cancelled.add(id));
        }

        private void finish(String id) {
            running.get(id).tryEmitEmpty();
        }
    }

    private final Calls calls = new Calls();

    private static SubRequest request(String id, String... dependencies) {
        SubRequest request = new SubRequest(SubRequestDto.builder()
            .referenceId(id).method("GET").url("/api/" + id).build());
        request.setDependencies(Set.of(dependencies));
        return request;
    }

    private BatchExecution batch(int limit, boolean stopOnFailure, SubRequest... requests) {
        Map<String, SubRequest> byId = new LinkedHashMap<>();
        for (SubRequest request : requests) {
            byId.put(request.getReferenceId(), request);
        }
        return new BatchExecution("batch", byId, calls, CONTEXT, limit, stopOnFailure);
    }

    private BatchExecution batch(SubRequest... requests) {
        return batch(5, false, requests);
    }

    private static SubResponse response(String id, int status) {
        return SubResponse.builder().referenceId(id).httpStatus(status).body("data").build();
    }

    @Test
    void start_runsOnlyRequestsWithoutDependencies() {
        batch(request("a"), request("b"), request("c", "a")).start();

        assertThat(calls.started).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void start_twice_runsEachRequestOnce() {
        BatchExecution batch = batch(request("a"));

        batch.start();
        batch.start();

        assertThat(calls.started).containsExactly("a");
    }

    @Test
    void response_unlocksDependents() {
        BatchExecution batch = batch(request("a"), request("b", "a"), request("c", "a"));
        batch.start();

        batch.addResponse("a", response("a", 200));

        assertThat(calls.started).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void dependent_waitsForEveryDependency() {
        BatchExecution batch = batch(request("a"), request("b"), request("c", "a", "b"));
        batch.start();

        batch.addResponse("a", response("a", 200));
        assertThat(calls.started).doesNotContain("c");

        batch.addResponse("b", response("b", 200));
        assertThat(calls.started).contains("c");
    }

    @Test
    void diamondGraph_runsInDependencyOrder() {
        BatchExecution batch = batch(request("a"), request("b", "a"), request("c", "a"),
            request("d", "b", "c"), request("e", "d"));
        batch.start();

        batch.addResponse("a", response("a", 200));
        batch.addResponse("b", response("b", 200));
        assertThat(calls.started).doesNotContain("d");
        batch.addResponse("c", response("c", 200));
        assertThat(calls.started).contains("d").doesNotContain("e");
        batch.addResponse("d", response("d", 200));

        assertThat(calls.started).contains("e");
    }

    @Test
    void firstResultWins_andIsCountedOnce() {
        BatchExecution batch = batch(request("a"), request("b", "a"), request("c"));
        SubResponse first = response("a", 200);
        batch.start();

        batch.addResponse("a", first);
        batch.addResponse("a", response("a", 503));

        assertThat(batch.responses().get("a")).isSameAs(first);
        assertThat(batch.future()).isNotDone();
        assertThat(calls.started.stream().filter("b"::equals)).hasSize(1);
    }

    @Test
    void future_completesWhenEveryResponseArrives() throws Exception {
        BatchExecution batch = batch(request("a"), request("b"));

        batch.addResponse("a", response("a", 200));
        assertThat(batch.future()).isNotDone();
        batch.addResponse("b", response("b", 201));

        CompositeResponse result = batch.future().get(1, TimeUnit.SECONDS);
        assertThat(result.getResponses()).containsOnlyKeys("a", "b");
        assertThat(result.isHasErrors()).isFalse();
    }

    @Test
    void future_reportsErrorsForNon2xxResponses() throws Exception {
        BatchExecution batch = batch(request("a"));

        batch.addResponse("a", response("a", 302));

        assertThat(batch.future().get(1, TimeUnit.SECONDS).isHasErrors()).isTrue();
    }

    @Test
    void concurrentResponses_completeTheBatch() throws Exception {
        int count = 10;
        SubRequest[] requests = new SubRequest[count];
        for (int i = 0; i < count; i++) {
            requests[i] = request("r" + i);
        }
        BatchExecution batch = batch(requests);
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch latch = new CountDownLatch(count);

        for (int i = 0; i < count; i++) {
            String id = "r" + i;
            executor.submit(() -> {
                try {
                    batch.addResponse(id, response(id, 200));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(batch.future().get(1, TimeUnit.SECONDS).getResponses()).hasSize(count);
    }

    @Test
    void failedDependency_records424WithoutRunningTheDependent() {
        BatchExecution batch = batch(request("a"), request("b", "a"));
        batch.start();

        batch.addResponse("a", response("a", 500));

        assertThat(calls.started).containsExactly("a");
        assertThat(batch.responses().get("b").getHttpStatus()).isEqualTo(424);
        assertThat(batch.responses().get("b").getBody().toString()).contains("Failed Dependency", "a");
        assertThat(batch.future()).isCompleted();
    }

    @Test
    void failedDependency_cascadesThroughALongChain() {
        BatchExecution batch = batch(request("a"), request("b", "a"), request("c", "b"), request("d", "c"));
        batch.start();

        batch.addResponse("a", response("a", 404));

        assertThat(calls.started).containsExactly("a");
        assertThat(batch.responses().get("d").getHttpStatus()).isEqualTo(424);
        assertThat(batch.future()).isCompleted();
    }

    @Test
    void concurrencyLimit_holds_andQueuedWorkDrains() {
        batch(1, false, request("a"), request("b"), request("c")).start();

        assertThat(calls.started).containsExactly("a");
        calls.finish("a");
        assertThat(calls.started).containsExactly("a", "b");
        calls.finish("b");
        assertThat(calls.started).containsExactly("a", "b", "c");
    }

    @Test
    void cancel_stopsQueuedWorkAndCancelsTheRunningCall() {
        BatchExecution batch = batch(1, false, request("a"), request("b"));
        batch.start();

        batch.cancel();

        assertThat(calls.cancelled).containsExactly("a");
        assertThat(calls.started).containsExactly("a");
    }

    @Test
    void cancel_preventsDependentDispatch() {
        BatchExecution batch = batch(request("a"), request("b", "a"));
        batch.start();
        batch.cancel();

        batch.addResponse("a", response("a", 200));

        assertThat(calls.started).containsExactly("a");
    }

    @Test
    void dispatchFailure_isRecorded_andDoesNotStrandQueuedWork() {
        calls.failing.add("a");
        BatchExecution batch = batch(1, false, request("a"), request("b"));

        batch.start();

        assertThat(batch.responses().get("a").getHttpStatus()).isEqualTo(500);
        assertThat(calls.started).containsExactly("a", "b");
    }

    @Test
    void stopOnFailure_marksRunningAndQueuedWorkDifferently() {
        BatchExecution batch = batch(1, true, request("running"), request("queued"), request("failed"));
        batch.start();

        batch.addResponse("failed", response("failed", 500));

        assertThat(calls.cancelled).containsExactly("running");
        assertThat(calls.started).containsExactly("running");
        assertThat(batch.responses().get("running").getHttpStatus()).isEqualTo(503);
        assertThat(batch.responses().get("running").getBody().toString()).contains("may have already");
        assertThat(batch.responses().get("queued").getHttpStatus()).isEqualTo(424);
        assertThat(batch.responses().get("queued").getBody().toString()).contains("Not executed");
        assertThat(batch.responses().get("failed").getHttpStatus()).isEqualTo(500);
        assertThat(batch.future()).isCompleted();
    }

    @Test
    void stopOnFailure_ignoresSuccessfulResponses() {
        BatchExecution batch = batch(5, true, request("a"), request("b", "a"));
        batch.start();

        batch.addResponse("a", response("a", 200));

        assertThat(calls.started).containsExactly("a", "b");
    }

    @Test
    void fail_completesTheFutureExceptionally() {
        BatchExecution batch = batch(request("a"));

        batch.fail(new RuntimeException("boom"));

        assertThat(batch.future()).isCompletedExceptionally();
    }

    @Test
    void unknownResponseId_isIgnored() {
        BatchExecution batch = batch(request("a"));

        batch.addResponse("other", response("other", 200));

        assertThat(batch.responses()).isEmpty();
        assertThat(batch.future()).isNotDone();
    }

    @Test
    void responsesView_isReadOnly() {
        BatchExecution batch = batch(request("a"));

        assertThatThrownBy(() -> batch.responses().put("a", response("a", 200)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_rejectsUnknownDependency() {
        assertThatThrownBy(() -> batch(request("a", "unknown")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown");
    }

    @Test
    void constructor_rejectsInvalidConcurrencyLimit() {
        assertThatThrownBy(() -> batch(0, false, request("a")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void synchronousCalls_finishTheWholeGraph() {
        AtomicBoolean[] holder = {new AtomicBoolean()};
        Map<String, SubRequest> requests = new LinkedHashMap<>();
        requests.put("a", request("a"));
        requests.put("b", request("b", "a"));
        requests.put("c", request("c", "b"));
        SubRequestForwarder immediate = (subRequest, execution) -> {
            execution.addResponse(subRequest.getReferenceId(), response(subRequest.getReferenceId(), 200));
            holder[0].set(true);
            return Mono.empty();
        };
        BatchExecution batch = new BatchExecution("batch", requests, immediate, CONTEXT, 1, false);

        batch.start();

        assertThat(holder[0]).isTrue();
        assertThat(batch.future()).isCompleted();
        assertThat(batch.responses()).containsOnlyKeys("a", "b", "c");
    }
}
