package io.github.nabilcarel.composite.model;

import lombok.Getter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class SubRequestCoordinatorImpl implements SubRequestCoordinator {

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

    private final Map<String, SubRequestNode> nodes = new ConcurrentHashMap<>();

    public SubRequestCoordinatorImpl(Map<String, Set<String>> dependencies) {
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
}
