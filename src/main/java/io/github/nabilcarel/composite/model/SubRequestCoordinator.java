package io.github.nabilcarel.composite.model;

import java.util.List;

public interface SubRequestCoordinator {
    List<String> getInitialReadySubRequests();
    List<String> markResolved(String id);
    boolean markInProgress(String id);
    boolean isResolved(String id);
    boolean isBatchResolved();
}
