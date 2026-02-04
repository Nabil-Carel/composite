package io.github.nabilcarel.composite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.nabilcarel.composite.model.SubRequestCoordinatorImpl;

class SubRequestCoordinatorImplTest {

    private Map<String, Set<String>> dependencies;

    @BeforeEach
    void setUp() {
        dependencies = new HashMap<>();
    }

    @Test
    void testGetInitialReadySubRequests_withNoDependencies() {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of());
        dependencies.put("c", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        List<String> ready = coordinator.getInitialReadySubRequests();

        assertThat(ready).hasSize(3)
                .contains("a", "b", "c");
    }

    @Test
    void testGetInitialReadySubRequests_withDependencies() {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of("a"));  // b depends on a
        dependencies.put("c", Set.of("b"));  // c depends on b

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        List<String> ready = coordinator.getInitialReadySubRequests();

        assertThat(ready).hasSize(1)
                .contains("a")
                .doesNotContain("b", "c");
    }

    @Test
    void testMarkResolved_triggersDependentRequests() {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of("a"));
        dependencies.put("c", Set.of("a"));

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        List<String> initial = coordinator.getInitialReadySubRequests();
        assertThat(initial).hasSize(1)
                .contains("a");

        coordinator.markInProgress("a");
        List<String> nowReady = coordinator.markResolved("a");
        
        assertThat(nowReady).hasSize(2)
                .contains("b", "c");
    }

    @Test
    void testMarkResolved_withMultipleDependencies() {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of());
        dependencies.put("c", Set.of("a", "b"));  // c depends on both a and b

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        List<String> initial = coordinator.getInitialReadySubRequests();
        assertThat(initial).hasSize(2);

        coordinator.markInProgress("a");
        List<String> afterA = coordinator.markResolved("a");
        assertThat(afterA).isEmpty();

        coordinator.markInProgress("b");
        List<String> afterB = coordinator.markResolved("b");
        assertThat(afterB).contains("c");
    }

    @Test
    void testMarkInProgress_onlyOnce() {
        dependencies.put("a", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        assertThat(coordinator.markInProgress("a")).isTrue();
        assertThat(coordinator.markInProgress("a")).isFalse();
    }

    @Test
    void testMarkResolved_onlyFromInProgress() {
        dependencies.put("a", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        List<String> result = coordinator.markResolved("a");
        assertThat(result).isEmpty();
    }

    @Test
    void testIsResolved() {
        dependencies.put("a", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        assertThat(coordinator.isResolved("a")).isFalse();

        coordinator.markInProgress("a");
        assertThat(coordinator.isResolved("a")).isFalse();

        coordinator.markResolved("a");
        assertThat(coordinator.isResolved("a")).isTrue();
    }

    @Test
    void testIsBatchResolved() {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        assertThat(coordinator.isBatchResolved()).isFalse();

        coordinator.markInProgress("a");
        coordinator.markResolved("a");
        assertThat(coordinator.isBatchResolved()).isFalse();

        coordinator.markInProgress("b");
        coordinator.markResolved("b");
        assertThat(coordinator.isBatchResolved()).isTrue();
    }

    @Test
    void testConcurrentMarkResolved() throws InterruptedException {
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of("a"));
        dependencies.put("c", Set.of("a"));

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        coordinator.markInProgress("a");
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<List<String>> results = Collections.synchronizedList(new ArrayList<>());

        IntStream.range(0, threadCount).forEach(i -> {
            executor.submit(() -> {
                try {
                    List<String> ready = coordinator.markResolved("a");
                    results.add(ready);
                } finally {
                    latch.countDown();
                }
            });
        });

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        long nonEmptyResults = results.stream().filter(r -> !r.isEmpty()).count();
        assertThat(nonEmptyResults).isEqualTo(1);
    }

    @Test
    void testConcurrentMarkInProgress() throws InterruptedException {
        dependencies.put("a", Set.of());

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Boolean> results = Collections.synchronizedList(new ArrayList<>());

        IntStream.range(0, threadCount).forEach(i -> {
            executor.submit(() -> {
                try {
                    boolean success = coordinator.markInProgress("a");
                    results.add(success);
                } finally {
                    latch.countDown();
                }
            });
        });

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertThat(successCount).isEqualTo(1);
    }

    @Test
    void testComplexDependencyGraph() {
        // A -> B, A -> C, B -> D, C -> D, D -> E
        dependencies.put("a", Set.of());
        dependencies.put("b", Set.of("a"));
        dependencies.put("c", Set.of("a"));
        dependencies.put("d", Set.of("b", "c"));
        dependencies.put("e", Set.of("d"));

        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        List<String> initial = coordinator.getInitialReadySubRequests();
        assertThat(initial).hasSize(1)
                .contains("a");

        coordinator.markInProgress("a");
        List<String> afterA = coordinator.markResolved("a");
        assertThat(afterA).hasSize(2)
                .contains("b", "c");

        coordinator.markInProgress("b");
        List<String> afterB = coordinator.markResolved("b");
        assertThat(afterB).doesNotContain("d");

        coordinator.markInProgress("c");
        List<String> afterC = coordinator.markResolved("c");
        assertThat(afterC).contains("d");

        coordinator.markInProgress("d");
        List<String> afterD = coordinator.markResolved("d");
        assertThat(afterD).contains("e");
    }

    @Test
    void testUnknownDependency_throwsException() {
        dependencies.put("a", Set.of("unknown"));

        assertThatThrownBy(() -> new SubRequestCoordinatorImpl(dependencies))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testMarkResolved_withNonExistentId() {
        dependencies.put("a", Set.of());
        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        List<String> result = coordinator.markResolved("nonexistent");
        assertThat(result).isEmpty();
    }

    @Test
    void testMarkInProgress_withNonExistentId() {
        dependencies.put("a", Set.of());
        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        assertThat(coordinator.markInProgress("nonexistent")).isFalse();
    }

    @Test
    void testIsResolved_withNonExistentId() {
        dependencies.put("a", Set.of());
        SubRequestCoordinatorImpl coordinator = new SubRequestCoordinatorImpl(dependencies);
        
        assertThat(coordinator.isResolved("nonexistent")).isFalse();
    }
}
