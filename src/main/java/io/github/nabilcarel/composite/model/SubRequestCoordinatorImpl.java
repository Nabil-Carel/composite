package io.github.nabilcarel.composite.model;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * Default {@link SubRequestCoordinator} implementation backed by a dependency DAG.
 *
 * <p>Each sub-request is modelled as a {@link SubRequestNode} with:
 * <ul>
 *   <li>A set of {@code dependsOn} reference IDs (its direct dependencies).</li>
 *   <li>A set of {@code dependents} — nodes that depend on this one (populated during
 *       {@link #buildDependencyGraph()}).</li>
 *   <li>An {@link java.util.concurrent.atomic.AtomicInteger AtomicInteger}
 *       {@code remainingDependencies} counter that is decremented as dependencies
 *       complete.</li>
 *   <li>An {@link java.util.concurrent.atomic.AtomicReference AtomicReference}
 *       {@code state} that transitions {@code PENDING → IN_PROGRESS → RESOLVED} using
 *       compare-and-set operations to prevent double-dispatch in concurrent scenarios.</li>
 * </ul>
 *
 * <p>Construction validates that all declared dependencies refer to known sub-request IDs;
 * an {@link IllegalArgumentException} is thrown for any unknown dependency. (This is a
 * defence-in-depth check — the validator should have already caught this before the
 * coordinator is created.)
 *
 * @see SubRequestCoordinator
 * @since 0.0.1
 */
public class SubRequestCoordinatorImpl implements SubRequestCoordinator {

    private final Map<String, SubRequestNode> nodes = new ConcurrentHashMap<>();

    public
    SubRequestCoordinatorImpl(Map<String, Set<String>> dependencies) {
        init(dependencies);
        buildDependencyGraph();
    }

    private void init(Map<String, Set<String>> dependencies) {
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String id = entry.getKey();
            Set<String> dependsOn = entry.getValue() != null ? entry.getValue() : new HashSet<>();
            nodes.put(id, new SubRequestNode(id, dependsOn));
        }
    }

    private void buildDependencyGraph() {
        for (SubRequestNode node : nodes.values()) {
            for (String depId : node.dependsOn) {
                SubRequestNode depNode = nodes.get(depId);
                if (depNode != null) {
                    depNode.addDependent(node.id);
                } else {
                    throw new IllegalArgumentException("Unknown dependency: " + depId);
                }
            }
        }
    }

    public List<String> getInitialReadySubRequests() {
        return nodes.values().stream()
                .filter(node -> node.getRemainingDependencies() == 0 && node.getState() == State.PENDING)
                .map(node -> node.id)
                .collect(Collectors.toList());
    }

    public List<String> markResolved(String id) {
        SubRequestNode node = nodes.get(id);
        if (node == null || !node.markResolved()) return List.of();

        List<String> ready = new ArrayList<>();
        for (String dependentId : node.getDependents()) {
            SubRequestNode dependent = nodes.get(dependentId);
            if (dependent != null) {
                int remaining = dependent.onDependencyResolved();
                if (remaining == 0 && dependent.getState() == State.PENDING) {
                    ready.add(dependentId);
                }
            }
        }
        return ready;
    }

    public boolean markInProgress(String id) {
        SubRequestNode node = nodes.get(id);
        return node != null && node.markInProgress();
    }

    public boolean isResolved(String id) {
        SubRequestNode node = nodes.get(id);
        return node != null && node.getState() == State.RESOLVED;
    }

    public boolean isBatchResolved() {
        return nodes.values().stream().allMatch(n -> n.getState() == State.RESOLVED);
    }

    public enum State {
        PENDING,
        IN_PROGRESS,
        RESOLVED
    }

    public static class SubRequestNode {
        private final String id;
        private final Set<String> dependsOn;
        @Getter
        private final Set<String> dependents = new HashSet<>();
        private final AtomicInteger remainingDependencies;
        private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

        public SubRequestNode(String id, Set<String> dependsOn) {
            this.id = id;
            this.dependsOn = dependsOn;
            this.remainingDependencies = new AtomicInteger(dependsOn.size());
        }

        public void addDependent(String dependentId) {
            dependents.add(dependentId);
        }

        public int onDependencyResolved() {
            return remainingDependencies.decrementAndGet();
        }

        public int getRemainingDependencies() {
            return remainingDependencies.get();
        }

        public State getState() {
            return state.get();
        }

        public boolean markInProgress() {
            return state.compareAndSet(State.PENDING, State.IN_PROGRESS);
        }

        public boolean markResolved() {
            return state.compareAndSet(State.IN_PROGRESS, State.RESOLVED);
        }
    }
}
