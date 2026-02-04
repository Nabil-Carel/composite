package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.model.ResponseTrackerImpl;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class ResponseTrackerImplTest {

    private ResponseTrackerImpl tracker;

    @BeforeEach
    void setUp() {
        tracker = new ResponseTrackerImpl(3);
    }

    @Test
    void testAddResponse_decrementsCounter() {
        SubResponse response = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data")
            .build();

        tracker.addResponse("test1", response);

        // Counter should be decremented (3 -> 2)
        // We can't directly check the counter, but we can check if future is not complete
        assertThat(tracker.getFuture().isDone()).isFalse();
    }

    @Test
    void testAddResponse_completesWhenAllReceived() throws InterruptedException, ExecutionException, TimeoutException {
        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();
        SubResponse response2 = SubResponse.builder()
            .referenceId("test2")
            .httpStatus(200)
            .body("data2")
            .build();
        SubResponse response3 = SubResponse.builder()
            .referenceId("test3")
            .httpStatus(200)
            .body("data3")
            .build();

        tracker.addResponse("test1", response1);
        assertThat(tracker.getFuture().isDone()).isFalse();

        tracker.addResponse("test2", response2);
        assertThat(tracker.getFuture().isDone()).isFalse();

        tracker.addResponse("test3", response3);
        
        // Should complete after all responses
        CompositeResponse compositeResponse = tracker.getFuture().get(1, TimeUnit.SECONDS);
        assertThat(compositeResponse).isNotNull();
        assertThat(compositeResponse.getResponses()).hasSize(3)
            .containsKeys("test1", "test2", "test3");
    }

    @Test
    void testAddResponse_triggersCallback() {
        AtomicInteger callbackCount = new AtomicInteger(0);
        List<String> resolvedIds = Collections.synchronizedList(new ArrayList<>());

        tracker.setOnSubRequestResolved(id -> {
            callbackCount.incrementAndGet();
            resolvedIds.add(id);
        });

        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();
        SubResponse response2 = SubResponse.builder()
            .referenceId("test2")
            .httpStatus(200)
            .body("data2")
            .build();

        tracker.addResponse("test1", response1);
        assertThat(callbackCount.get()).isEqualTo(1);
        assertThat(resolvedIds).contains("test1");

        tracker.addResponse("test2", response2);
        assertThat(callbackCount.get()).isEqualTo(2);
        assertThat(resolvedIds).contains("test2");
    }

    @Test
    void testAddResponse_concurrentAccess() throws InterruptedException, ExecutionException, TimeoutException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ResponseTrackerImpl concurrentTracker = new ResponseTrackerImpl(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    SubResponse response = SubResponse.builder()
                        .referenceId("test" + id)
                        .httpStatus(200)
                        .body("data" + id)
                        .build();
                    concurrentTracker.addResponse("test" + id, response);
                    successCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(threadCount);

        // Future should be complete
        CompositeResponse result = concurrentTracker.getFuture().get(1, TimeUnit.SECONDS);
        assertThat(result.getResponses()).hasSize(threadCount);
    }

    @Test
    void testCancel_completesExceptionally() {
        tracker.cancel(new RuntimeException("Test error"));

        assertThat(tracker.getFuture().isDone()).isTrue();
        assertThat(tracker.getFuture().isCompletedExceptionally()).isTrue();

        assertThatThrownBy(() -> tracker.getFuture().get())
            .isInstanceOf(ExecutionException.class);
    }

    @Test
    void testGetSubResponseMap_returnsAllResponses() {
        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();
        SubResponse response2 = SubResponse.builder()
            .referenceId("test2")
            .httpStatus(200)
            .body("data2")
            .build();

        tracker.addResponse("test1", response1);
        tracker.addResponse("test2", response2);

        Map<String, SubResponse> responseMap = tracker.getSubResponseMap();
        assertThat(responseMap).hasSize(2)
            .containsEntry("test1", response1)
            .containsEntry("test2", response2);
    }

    @Test
    void testAddResponse_overwritesPreviousResponse() {
        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();
        SubResponse response2 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(201)
            .body("data2")
            .build();

        tracker.addResponse("test1", response1);
        tracker.addResponse("test1", response2);  // Overwrite

        Map<String, SubResponse> responseMap = tracker.getSubResponseMap();
        assertThat(responseMap).hasSize(1)
            .containsEntry("test1", response2);
    }

    @Test
    void testCallbackInvocationOrder() {
        List<String> invocationOrder = Collections.synchronizedList(new ArrayList<>());

        tracker.setOnSubRequestResolved(invocationOrder::add);

        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();
        SubResponse response2 = SubResponse.builder()
            .referenceId("test2")
            .httpStatus(200)
            .body("data2")
            .build();
        SubResponse response3 = SubResponse.builder()
            .referenceId("test3")
            .httpStatus(200)
            .body("data3")
            .build();

        tracker.addResponse("test1", response1);
        tracker.addResponse("test2", response2);
        tracker.addResponse("test3", response3);

        assertThat(invocationOrder).hasSize(3)
            .containsExactly("test1", "test2", "test3");
    }

    @Test
    void testSetCallbackAfterResponses() {
        SubResponse response1 = SubResponse.builder()
            .referenceId("test1")
            .httpStatus(200)
            .body("data1")
            .build();

        tracker.addResponse("test1", response1);

        // Setting callback after response should still work for future responses
        AtomicInteger callbackCount = new AtomicInteger(0);
        tracker.setOnSubRequestResolved(id -> callbackCount.incrementAndGet());

        SubResponse response2 = SubResponse.builder()
            .referenceId("test2")
            .httpStatus(200)
            .body("data2")
            .build();

        tracker.addResponse("test2", response2);
        assertThat(callbackCount.get()).isEqualTo(1);
    }
}
