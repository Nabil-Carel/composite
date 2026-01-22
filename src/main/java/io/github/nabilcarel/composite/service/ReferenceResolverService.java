package io.github.nabilcarel.composite.service;

import io.github.nabilcarel.composite.model.request.SubRequest;

public interface ReferenceResolverService {
  public String resolveUrl(SubRequest subRequest, String batchId);

  public void resolveBody(SubRequest subRequest, String batchId);

  public void resolveHeaders(SubRequest subRequest, String batchId);
}
