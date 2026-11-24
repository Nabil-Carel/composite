package io.github.nabilcarel.composite.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.ResponseTrackerImpl;
import io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestContext;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.CompositeBatchContextImpl;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.http.ResponseEntity;
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
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");
        return request;
    }

    /** Records dispatches and returns a call chosen by the test. */
    private static final class FakeService implements CompositeRequestService {
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final AtomicInteger total = new AtomicInteger();
        private final Function<String, Mono<Void>> callFor;

        private FakeService(Function<String, Mono<Void>> callFor) {
            this.callFor = callFor;
        }

        @Override
        public Mono<Void> forwardSubrequest(SubRequest subRequest, String requestId, SubRequestContext context) {
            calls.computeIfAbsent(subRequest.getReferenceId(), id -> new AtomicInteger()).incrementAndGet();
            total.incrementAndGet();
            return callFor.apply(subRequest.getReferenceId());
        }

        private int calls(String id) {
            AtomicInteger count = calls.get(id);
            return count == null ? 0 : count.get();
        }

        @Override
        public CompletableFuture<ResponseEntity<CompositeResponse>> execute(CompositeRequest compositeRequest,
                                                                           HttpServletRequest request,
                                                                           HttpServletResponse response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
            return Set.of();
        }
    }

    @JCStressTest
    @Description("Two dependencies finishing together make their dependent ready exactly once.")
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "Exactly one thread unlocks the dependent")
    @Outcome(expect = FORBIDDEN, desc = "Dependent was lost or dispatched twice")
    @State
    public static class DependentBecomesReadyOnce {
        private final SubRequestCoordinatorImpl coordinator =
            new SubRequestCoordinatorImpl(Map.of("a", Set.of(), "b", Set.of(), "c", Set.of("a", "b")));

        public DependentBecomesReadyOnce() {
            coordinator.markInProgress("a");
            coordinator.markInProgress("b");
        }

        @Actor
        public void first(II_Result result) {
            result.r1 = coordinator.markResolved("a").size();
        }

        @Actor
        public void second(II_Result result) {
            result.r2 = coordinator.markResolved("b").size();
        }
    }

    @JCStressTest
    @Description("Only one thread can claim a ready sub-request.")
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "One winner")
    @Outcome(expect = FORBIDDEN, desc = "Double dispatch or no dispatch")
    @State
    public static class SubRequestClaimedOnce {
        private final SubRequestCoordinatorImpl coordinator =
            new SubRequestCoordinatorImpl(Map.of("a", Set.of()));

        @Actor
        public void first(II_Result result) {
            result.r1 = coordinator.markInProgress("a") ? 1 : 0;
        }

        @Actor
        public void second(II_Result result) {
            result.r2 = coordinator.markInProgress("a") ? 1 : 0;
        }
    }

    @JCStressTest
    @Description("Resolving the same sub-request twice unlocks its dependent once.")
    @Outcome(id = {"1, 0", "0, 1"}, expect = ACCEPTABLE, desc = "One resolution counted")
    @Outcome(expect = FORBIDDEN, desc = "Dependency counted twice or not at all")
    @State
    public static class SubRequestResolvedOnce {
        private final SubRequestCoordinatorImpl coordinator =
            new SubRequestCoordinatorImpl(Map.of("a", Set.of(), "b", Set.of("a")));

        public SubRequestResolvedOnce() {
            coordinator.markInProgress("a");
        }

        @Actor
        public void first(II_Result result) {
            result.r1 = coordinator.markResolved("a").size();
        }

        @Actor
        public void second(II_Result result) {
            result.r2 = coordinator.markResolved("a").size();
        }
    }

    @JCStressTest
    @Description("The batch completes with every response after concurrent results.")
    @Outcome(id = "1, 2", expect = ACCEPTABLE, desc = "Complete, with both callbacks")
    @Outcome(expect = FORBIDDEN, desc = "Batch hung or lost a response")
    @State
    public static class TrackerCompletesWithAllResponses {
        private final ResponseTrackerImpl tracker = new ResponseTrackerImpl(2);
        private final AtomicInteger callbacks = new AtomicInteger();

        public TrackerCompletesWithAllResponses() {
            tracker.setOnSubRequestResolved(id -> callbacks.incrementAndGet());
        }

        @Actor
        public void first() {
            tracker.addResponse("a", response("a", 200));
        }

        @Actor
        public void second() {
            tracker.addResponse("b", response("b", 200));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            CompositeResponse response = tracker.getFuture().getNow(null);
            result.r1 = response != null && response.getResponses().size() == 2 ? 1 : 0;
            result.r2 = callbacks.get();
        }
    }

    @JCStressTest
    @Description("Two results for one sub-request are counted once.")
    @Outcome(id = "0, 1", expect = ACCEPTABLE, desc = "Batch still waits for the other sub-request")
    @Outcome(expect = FORBIDDEN, desc = "Duplicate result completed the batch early")
    @State
    public static class TrackerCountsDuplicateOnce {
        private final ResponseTrackerImpl tracker = new ResponseTrackerImpl(2);
        private final AtomicInteger callbacks = new AtomicInteger();

        public TrackerCountsDuplicateOnce() {
            tracker.setOnSubRequestResolved(id -> callbacks.incrementAndGet());
        }

        @Actor
        public void result() {
            tracker.addResponse("a", response("a", 200));
        }

        @Actor
        public void cancellation() {
            tracker.addResponse("a", response("a", 503));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            result.r1 = tracker.getFuture().isDone() ? 1 : 0;
            result.r2 = callbacks.get();
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
        private final ResponseTrackerImpl tracker = new ResponseTrackerImpl(2);
        private final FakeService service = new FakeService(id -> Mono.never());
        private final CompositeBatchContextImpl batch;

        public StopNeverHidesARunningCall() {
            Map<String, SubRequest> requests = new HashMap<>();
            requests.put("failed", subRequest("failed"));
            requests.put("queued", subRequest("queued"));
            SubRequestCoordinatorImpl coordinator =
                new SubRequestCoordinatorImpl(Map.of("failed", Set.of(), "queued", Set.of()));
            coordinator.markInProgress("failed");
            batch = new CompositeBatchContextImpl(tracker, coordinator, requests, service, "batch", CONTEXT, 5, true);
        }

        @Actor
        public void dispatch() {
            batch.startInitialRequests();
        }

        @Actor
        public void failure() {
            tracker.addResponse("failed", response("failed", 500));
        }

        @Arbiter
        public void arbiter(II_Result result) {
            result.r1 = service.calls("queued");
            SubResponse queued = tracker.getSubResponseMap().get("queued");
            result.r2 = queued == null ? 0 : queued.getHttpStatus();
        }
    }

    @JCStressTest
    @Description("Concurrent producers cannot exceed the per-batch concurrency limit.")
    @Outcome(id = "1", expect = ACCEPTABLE, desc = "Exactly one call runs under a limit of one")
    @Outcome(expect = FORBIDDEN, desc = "Limit exceeded or no work started")
    @State
    public static class ConcurrencyLimitHolds {
        private final ResponseTrackerImpl tracker = new ResponseTrackerImpl(3);
        private final FakeService service = new FakeService(id -> Mono.never());
        private final CompositeBatchContextImpl batch;

        public ConcurrencyLimitHolds() {
            Map<String, SubRequest> requests = new HashMap<>();
            requests.put("initial", subRequest("initial"));
            requests.put("done", subRequest("done"));
            requests.put("dependent", subRequest("dependent"));
            SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(Map.of(
                "initial", Set.of(), "done", Set.of(), "dependent", Set.of("done")));
            coordinator.markInProgress("done");
            batch = new CompositeBatchContextImpl(tracker, coordinator, requests, service, "batch", CONTEXT, 1);
        }

        @Actor
        public void initial() {
            batch.startInitialRequests();
        }

        @Actor
        public void dependent() {
            tracker.addResponse("done", response("done", 200));
        }

        @Arbiter
        public void arbiter(I_Result result) {
            result.r1 = service.total.get();
        }
    }

    @JCStressTest
    @Description("Work queued while a slot is released is never stranded.")
    @Outcome(id = "2", expect = ACCEPTABLE, desc = "The queued sub-request started")
    @Outcome(expect = FORBIDDEN, desc = "Queued work was stranded or started twice")
    @State
    public static class QueuedWorkIsNeverStranded {
        private final ResponseTrackerImpl tracker = new ResponseTrackerImpl(3);
        private final Sinks.Empty<Void> running = Sinks.empty();
        private final FakeService service =
            new FakeService(id -> id.equals("running") ? running.asMono() : Mono.never());

        public QueuedWorkIsNeverStranded() {
            Map<String, SubRequest> requests = new HashMap<>();
            requests.put("running", subRequest("running"));
            requests.put("done", subRequest("done"));
            requests.put("dependent", subRequest("dependent"));
            SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(Map.of(
                "running", Set.of(), "done", Set.of(), "dependent", Set.of("done")));
            coordinator.markInProgress("done");
            new CompositeBatchContextImpl(tracker, coordinator, requests, service, "batch", CONTEXT, 1)
                .startInitialRequests();
        }

        @Actor
        public void releaseSlot() {
            running.tryEmitEmpty();
        }

        @Actor
        public void queueDependent() {
            tracker.addResponse("done", response("done", 200));
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
        private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor(1);
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
        private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor(1);
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
