package io.github.nabilcarel.composite.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.internal.BatchExecution;
import io.github.nabilcarel.composite.internal.DispatchMarker;
import io.github.nabilcarel.composite.internal.SubRequestForwarder;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Description;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Concurrency invariants of the batch scheduler, tracker and handler limit. */
public final class CompositeConcurrencyStress {

    private CompositeConcurrencyStress() {
    }

    private static final SubRequestContext CONTEXT = new SubRequestContext(new HttpHeaders(), null);
    private static final HandlerMethod HANDLER = handler();
    private static final DispatchMarker MARKER = new DispatchMarker();

    public static final class Handlers {
        @CompositeEndpoint
        public void annotated() {
        }
    }

    private static HandlerMethod handler() {
        try {
            return new HandlerMethod(new Handlers(), Handlers.class.getMethod("annotated"));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SubRequest subRequest(String id) {
        return new SubRequest(SubRequestDto.builder().referenceId(id).method("GET").url("/x/" + id).build());
    }

    private static SubResponse response(String id, int status) {
        return SubResponse.builder().referenceId(id).httpStatus(status).build();
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());
        return request;
    }

    private static SubRequest subRequest(String id, String... dependencies) {
        SubRequest request = subRequest(id);
        request.setDependencies(Set.of(dependencies));
        return request;
    }

    private static BatchExecution batch(FakeService service, int limit, boolean stopOnFailure, SubRequest... requests) {
        Map<String, SubRequest> byId = new LinkedHashMap<>();
        for (SubRequest request : requests) {
            byId.put(request.getReferenceId(), request);
        }
        return new BatchExecution("batch", byId, service, CONTEXT, limit, stopOnFailure);
    }

    /** Records dispatches and returns a call chosen by the test. */
    private static final class FakeService implements SubRequestForwarder {
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final AtomicInteger total = new AtomicInteger();
        private final Function<String, Mono<Void>> callFor;

        private FakeService(Function<String, Mono<Void>> callFor) {
            this.callFor = callFor;
        }

        @Override
        public Mono<Void> forwardSubrequest(SubRequest subRequest, BatchExecution batch) {
            calls.computeIfAbsent(subRequest.getReferenceId(), id -> new AtomicInteger()).incrementAndGet();
            total.incrementAndGet();
            return callFor.apply(subRequest.getReferenceId());
        }

        private int calls(String id) {
            AtomicInteger count = calls.get(id);
            return count == null ? 0 : count.get();
        }
    }

    @JCStressTest
    @Description("Two dependencies finishing together start their dependent exactly once.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Dependent started once")
    @Outcome(expect = FORBIDDEN, desc = "Dependent was lost or dispatched twice")
    @State
    public static class DependentBecomesReadyOnce {
        private final FakeService service = new FakeService(id -> Mono.never());
        private final BatchExecution batch =
            batch(service, 5, false, subRequest("a"), subRequest("b"), subRequest("c", "a", "b"));

        public DependentBecomesReadyOnce() {
            batch.start();
        }

        @Actor
        public void first() {
            batch.addResponse("a", response("a", 200));
        }

        @Actor
        public void second() {
            batch.addResponse("b", response("b", 200));
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.calls("c");
        }
    }

    @JCStressTest
    @Description("Concurrent starts dispatch a ready sub-request once.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "One dispatch")
    @Outcome(expect = FORBIDDEN, desc = "Double dispatch or no dispatch")
    @State
    public static class SubRequestClaimedOnce {
        private final FakeService service = new FakeService(id -> Mono.never());
        private final BatchExecution batch = batch(service, 5, false, subRequest("a"));

        @Actor
        public void first() {
            batch.start();
        }

        @Actor
        public void second() {
            batch.start();
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.calls("a");
        }
    }

    @JCStressTest
    @Description("Resolving the same sub-request twice unlocks its dependent once.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "One resolution counted")
    @Outcome(expect = FORBIDDEN, desc = "Dependency counted twice or not at all")
    @State
    public static class SubRequestResolvedOnce {
        private final FakeService service = new FakeService(id -> Mono.never());
        private final BatchExecution batch = batch(service, 5, false, subRequest("a"), subRequest("b", "a"));

        public SubRequestResolvedOnce() {
            batch.start();
        }

        @Actor
        public void first() {
            batch.addResponse("a", response("a", 200));
        }

        @Actor
        public void second() {
            batch.addResponse("a", response("a", 200));
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.calls("b");
        }
    }

    @JCStressTest
    @Description("The batch completes with every response after concurrent results.")
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "Complete, with both responses")
    @Outcome(expect = FORBIDDEN, desc = "Batch hung or lost a response")
    @State
    public static class BatchCompletesWithAllResponses {
        private final BatchExecution batch =
            batch(new FakeService(id -> Mono.never()), 5, false, subRequest("a"), subRequest("b"));

        @Actor
        public void first() {
            batch.addResponse("a", response("a", 200));
        }

        @Actor
        public void second() {
            batch.addResponse("b", response("b", 200));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            CompositeResponse response = batch.future().getNow(null);
            result.r1 = response != null && response.getResponses().size() == 2 ? 1 : 0;
            result.r2 = batch.responses().size();
        }
    }

    @JCStressTest
    @Description("Two results for one sub-request are counted once.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Batch still waits for the other sub-request")
    @Outcome(expect = FORBIDDEN, desc = "Duplicate result completed the batch early")
    @State
    public static class BatchCountsDuplicateOnce {
        private final BatchExecution batch =
            batch(new FakeService(id -> Mono.never()), 5, false, subRequest("a"), subRequest("b"));

        @Actor
        public void result() {
            batch.addResponse("a", response("a", 200));
        }

        @Actor
        public void cancellation() {
            batch.addResponse("a", response("a", 503));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            result.r1 = batch.future().isDone() ? 1 : 0;
            result.r2 = batch.responses().size();
        }
    }

    @JCStressTest
    @Description("stopOnFailure never reports a sub-request as not executed if it was dispatched.")
    @Outcome(id = "0, 424", expect = ACCEPTABLE, desc = "Stopped first and never ran")
    @Outcome(id = "0, 503", expect = ACCEPTABLE, desc = "Claimed, then conservatively reported as unknown")
    @Outcome(id = "1, 503", expect = ACCEPTABLE, desc = "Ran and reported as unknown")
    @Outcome(expect = FORBIDDEN, desc = "A running call was reported as not executed")
    @State
    public static class StopNeverHidesARunningCall {
        private final FakeService service = new FakeService(id -> Mono.never());
        private final BatchExecution batch = batch(service, 5, true, subRequest("failed"), subRequest("queued"));

        @Actor
        public void dispatch() {
            batch.start();
        }

        @Actor
        public void failure() {
            batch.addResponse("failed", response("failed", 500));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            result.r1 = service.calls("queued");
            SubResponse queued = batch.responses().get("queued");
            result.r2 = queued == null ? 0 : queued.getHttpStatus();
        }
    }

    @JCStressTest
    @Description("Concurrent producers cannot exceed the per-batch concurrency limit.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Exactly one call runs under a limit of one")
    @Outcome(expect = FORBIDDEN, desc = "Limit exceeded or no work started")
    @State
    public static class ConcurrencyLimitHolds {
        private final FakeService service = new FakeService(id -> Mono.never());
        private final BatchExecution batch = batch(service, 1, false,
            subRequest("initial"), subRequest("done"), subRequest("dependent", "done"));

        @Actor
        public void initial() {
            batch.start();
        }

        @Actor
        public void dependent() {
            batch.addResponse("done", response("done", 200));
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.total.get();
        }
    }

    @JCStressTest
    @Description("Work queued while a slot is released is never stranded.")
    @Outcome(id = "3", expect = ACCEPTABLE, desc = "The queued sub-request started")
    @Outcome(expect = FORBIDDEN, desc = "Queued work was stranded or started twice")
    @State
    public static class QueuedWorkIsNeverStranded {
        private final Sinks.Empty<Void> running = Sinks.empty();
        private final FakeService service =
            new FakeService(id -> id.equals("running") ? running.asMono() : Mono.never());
        private final BatchExecution batch = batch(service, 2, false,
            subRequest("running"), subRequest("done"), subRequest("dependent", "done"));

        public QueuedWorkIsNeverStranded() {
            batch.start();
        }

        @Actor
        public void releaseSlot() {
            running.tryEmitEmpty();
        }

        @Actor
        public void queueDependent() {
            batch.addResponse("done", response("done", 200));
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.total.get();
        }
    }

    @JCStressTest
    @Description("The handler limit admits one of two concurrent sub-requests.")
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "One handler admitted")
    @Outcome(expect = FORBIDDEN, desc = "Limit exceeded or both rejected")
    @State
    public static class HandlerLimitAdmitsOne {
        private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor(1, MARKER);
        private final MockHttpServletRequest firstRequest = loopbackRequest();
        private final MockHttpServletRequest secondRequest = loopbackRequest();

        @Actor
        public void first(II_Result result) {
            result.r1 = interceptor.preHandle(firstRequest, new MockHttpServletResponse(), HANDLER) ? 1 : 0;
        }

        @Actor
        public void second(II_Result result) {
            result.r2 = interceptor.preHandle(secondRequest, new MockHttpServletResponse(), HANDLER) ? 1 : 0;
        }
    }

    @JCStressTest
    @Description("Concurrent release and acquire neither leak nor create a handler permit.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Exactly one permit remains")
    @Outcome(expect = FORBIDDEN, desc = "A permit leaked or was duplicated")
    @State
    public static class HandlerPermitIsConserved {
        private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor(1, MARKER);
        private final MockHttpServletRequest holder = loopbackRequest();
        private final MockHttpServletRequest contender = loopbackRequest();
        private boolean admitted;

        public HandlerPermitIsConserved() {
            interceptor.preHandle(holder, new MockHttpServletResponse(), HANDLER);
        }

        @Actor
        public void release() {
            interceptor.afterCompletion(holder, new MockHttpServletResponse(), HANDLER, null);
        }

        @Actor
        public void acquire() {
            admitted = interceptor.preHandle(contender, new MockHttpServletResponse(), HANDLER);
        }

        @Arbiter
        public void arbiter(I_Result result) {
            if (admitted) {
                interceptor.afterCompletion(contender, new MockHttpServletResponse(), HANDLER, null);
            }
            int available = 0;
            for (int i = 0; i < 3; i++) {
                if (interceptor.preHandle(loopbackRequest(), new MockHttpServletResponse(), HANDLER)) {
                    available++;
                }
            }
            result.r1 = available;
        }
    }
}
