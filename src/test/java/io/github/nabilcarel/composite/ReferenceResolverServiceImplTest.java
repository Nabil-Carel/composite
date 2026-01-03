package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.ReferenceResolverServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Disabled
@ExtendWith(MockitoExtension.class)
class ReferenceResolverServiceImplTest {

    @Mock
    private ConcurrentMap<String, ResponseTracker> responseStore;
    @Mock
    private ResponseTracker tracker;
    private final ObjectMapper mapper = new ObjectMapper();
    private SubRequest subRequest;
    private final String batchId = "test-batch-id";

    private ReferenceResolverServiceImpl referenceResolverService;

    @BeforeEach
    void setUp() {
        referenceResolverService = new ReferenceResolverServiceImpl(responseStore, mapper);

        subRequest = new SubRequest(SubRequestDto.builder().build());
        subRequest.setUrl("http://test.com/api/${user[name]}");
        subRequest.setHeaders(new HashMap<>());
        subRequest.setBody(null);
        subRequest.getNodeReferences().addAll(Collections.emptyList());
    }

    private void createStubs() {
        when(responseStore.get(batchId)).thenReturn(tracker);

        Map<String, SubResponse> subResponseMap = new HashMap<>();
        SubResponse subResponse = new SubResponse();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "John%20Doe");
        body.put("city", "New%20York");
        subResponse.setBody(body);
        subResponseMap.put("user", subResponse);
        when(tracker.getSubResponseMap()).thenReturn(subResponseMap);
    }

    @Test
    void testResolveUrl_withPlaceholder() {
        createStubs();
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/John%20Doe", resolvedUrl);
        assertEquals("http://test.com/api/John%20Doe", subRequest.getResolvedUrl());
    }

    @Test
    void testResolveUrl_withMultiplePlaceholders() {
        createStubs();
        subRequest.setUrl("http://test.com/api/${user.name}/city/${user.city}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/John%20Doe/city/New%20York", resolvedUrl);
    }

    @Test
    void testResolveHeaders_withPlaceholder() {
        createStubs();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-User", "${user.name}");
        subRequest.setHeaders(headers);
        referenceResolverService.resolveHeaders(subRequest, batchId);
        assertEquals("John%20Doe", subRequest.getResolvedHeaders().get("X-User"));
    }

    @Test
    void testResolveHeaders_noPlaceholders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Static", "static-value");
        subRequest.setHeaders(headers);
        referenceResolverService.resolveHeaders(subRequest, batchId);
        assertEquals("static-value", subRequest.getResolvedHeaders().get("X-Static"));
    }

    @Test
    void testResolveBody_withNoNodeReferences() {
        subRequest.getNodeReferences().addAll(Collections.emptyList());
        referenceResolverService.resolveBody(subRequest, batchId);
        // No exception should be thrown, nothing to resolve
    }

    @Test
    void testResolveUrl_invalidPlaceholder_throwsException() {
        createStubs();
        subRequest.setUrl("http://test.com/api/${invalid}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                referenceResolverService.resolveUrl(subRequest, batchId));
        assertTrue(ex.getMessage().contains("Invalid placeholder"));
    }

    @Test
    void testResolveUrl_missingResponseBody_throwsException() {
        // Remove user from subResponseMap
        ResponseTracker tracker = responseStore.get(batchId);
        tracker.getSubResponseMap().remove("user");
        subRequest.setUrl("http://test.com/api/${user.name}");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                referenceResolverService.resolveUrl(subRequest, batchId));
        assertTrue(ex.getMessage().contains("No response body found for reference ID"));
    }
}