package com.example.composite.model;

import java.util.List;
import java.util.Set;

public interface SubRequestCoordinator {
    List<String> getInitialReadySubRequests();
    List<String> markResolved(String id);
    boolean markInProgress(String id);
    boolean isResolved(String id);
    boolean isBatchResolved();
}
