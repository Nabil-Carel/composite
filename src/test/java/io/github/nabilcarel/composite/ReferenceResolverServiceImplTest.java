package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.ReferenceResolverServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
        subRequest.setUrl("http://test.com/api/${user['name']}");
        subRequest.setHeaders(new HashMap<>());
        subRequest.setBody(null);
        subRequest.getNodeReferences().addAll(Collections.emptyList());
    }

    private Map<String, SubResponse> createStubs() {
        when(responseStore.get(batchId)).thenReturn(tracker);

        Map<String, SubResponse> subResponseMap = new HashMap<>();
        SubResponse subResponse = new SubResponse();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "John Doe");
        body.put("city", "New York");
        subResponse.setBody(body);
        subResponseMap.put("user", subResponse);
        when(tracker.getSubResponseMap()).thenReturn(subResponseMap);
        return subResponseMap;
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
        assertEquals("John Doe", subRequest.getResolvedHeaders().get("X-User"));
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
        assertDoesNotThrow(() -> referenceResolverService.resolveBody(subRequest, batchId));
    }

    @Test
    void testResolveUrl_invalidPlaceholder_throwsException() {
        createStubs();
        subRequest.setUrl("http://test.com/api/${invalid}");
        ReferenceResolutionException ex = assertThrows(ReferenceResolutionException.class, () ->
                referenceResolverService.resolveUrl(subRequest, batchId));
        assertTrue(ex.getMessage().contains("No response found for reference ID"));
    }

    @Test
    void testResolveUrl_missingResponseBody_throwsException() {
        createStubs();
        ResponseTracker localTracker = responseStore.get(batchId);
        localTracker.getSubResponseMap().remove("user");
        subRequest.setUrl("http://test.com/api/${user.name}");
        ReferenceResolutionException ex = assertThrows(ReferenceResolutionException.class, () ->
                referenceResolverService.resolveUrl(subRequest, batchId));
        assertTrue(ex.getMessage().contains("No response found for reference ID"));
    }

    // ========== EDGE CASES TO REVEAL BUGS ==========

    @Test
    void testResolveUrl_withBracketNotation_arrayIndex() {
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(java.util.Arrays.asList(
            Map.of("id", "1", "name", "Alice"),
            Map.of("id", "2", "name", "Bob")
        ));
        map.put("users", usersResponse);

        subRequest.setUrl("http://test.com/api/users/${users[0].id}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        // This test will reveal if bracket notation parsing works correctly
        assertEquals("http://test.com/api/users/1", resolvedUrl);
    }

    @Test
    void testResolveUrl_withBracketNotation_stringKey() {
        Map<String, SubResponse> map = createStubs();
        SubResponse configResponse = new SubResponse();
        Map<String, Object> configBody = new HashMap<>();
        configBody.put("database.host", "localhost");
        configBody.put("database.port", "5432");
        configResponse.setBody(configBody);
        map.put("config", configResponse);

        subRequest.setUrl("http://test.com/api/${config['database.host']}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        // This will test bracket notation with quoted keys
        assertEquals("http://test.com/api/localhost", resolvedUrl);
    }

    @Test
    void testResolveUrl_withSpaceInValue_shouldBeUrlEncoded() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("name", "John Doe");  // Space in value
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        subRequest.setUrl("http://test.com/api/users/${user.name}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        // This test will reveal if URL encoding is applied
        // Expected: "John%20Doe" or "John+Doe", not "John Doe"
        assertTrue(resolvedUrl.contains("John%20Doe") || resolvedUrl.contains("John+Doe"),
            "URL should be encoded. Got: " + resolvedUrl);
    }

    @Test
    void testResolveUrl_withSpecialCharacters_shouldBeUrlEncoded() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("email", "user@example.com");
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        subRequest.setUrl("http://test.com/api/users/${user.email}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        // @ should be encoded as %40
        assertTrue(resolvedUrl.contains("%40") || resolvedUrl.contains("user@example.com"),
            "Special characters should be encoded. Got: " + resolvedUrl);
    }

    @Test
    void testResolveUrl_withNestedPlaceholders() {
        Map<String, SubResponse> map = createStubs();
        
        // First response contains a reference to another
        SubResponse refResponse = new SubResponse();
        Map<String, Object> refBody = new HashMap<>();
        refBody.put("target", "user");
        refResponse.setBody(refBody);
        map.put("ref", refResponse);

        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("name", "John");
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        // This tests nested resolution: ${${ref.target}.name}
        // First resolve ${ref.target} -> "user", then ${user.name} -> "John"
        subRequest.setUrl("http://test.com/api/${${ref.target}.name}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/John", resolvedUrl);
    }

    @Test
    void testResolveUrl_withCircularReference_shouldDetectAndFail() {
        Map<String, SubResponse> map = createStubs();
        
        SubResponse aResponse = new SubResponse();
        Map<String, Object> aBody = new HashMap<>();
        aBody.put("value", "${b.value}");  // References b
        aResponse.setBody(aBody);
        map.put("a", aResponse);

        SubResponse bResponse = new SubResponse();
        Map<String, Object> bBody = new HashMap<>();
        bBody.put("value", "${a.value}");  // References a (circular!)
        bResponse.setBody(bBody);
        map.put("b", bResponse);

        subRequest.setUrl("http://test.com/api/${a.value}");
        // Should detect circular reference and throw exception
        assertThrows(IllegalArgumentException.class, () ->
            referenceResolverService.resolveUrl(subRequest, batchId));
    }

    @Test
    void testResolveBody_withArrayElementReference() {
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(java.util.Arrays.asList(
            Map.of("id", "1"),
            Map.of("id", "2")
        ));
        map.put("users", usersResponse);

        try {
            JsonNode body = mapper.readTree("{\"userId\": \"${users[0].id}\"}");
            SubRequestDto dto = SubRequestDto.builder()
                .url("/api/test")
                .method("POST")
                .referenceId("test")
                .body(body)
                .build();
            SubRequest request = new SubRequest(dto);

            request.getDependencies();
            referenceResolverService.resolveBody(request, batchId);
            
            // Body should be resolved
            assertNotNull(request.getBody());
            String resolvedValue = request.getBody().get("userId").asText();
            assertEquals("1", resolvedValue);
        } catch (Exception e) {
            fail("Should not throw exception: " + e.getMessage());
        }
    }

    @Test
    void testResolveBody_withNestedObjectReference() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        Map<String, Object> address = new HashMap<>();
        address.put("city", "New York");
        userBody.put("address", address);
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        try {
            JsonNode body = mapper.readTree("{\"city\": \"${user.address.city}\"}");
            SubRequestDto dto = SubRequestDto.builder()
                .url("/api/test")
                .method("POST")
                .referenceId("test")
                .body(body)
                .build();
            SubRequest request = new SubRequest(dto);

            request.getDependencies();
            referenceResolverService.resolveBody(request, batchId);
            
            String resolvedValue = request.getBody().get("city").asText();
            assertEquals("New York", resolvedValue);
        } catch (Exception e) {
            fail("Should not throw exception: " + e.getMessage());
        }
    }

    @Test
    void testResolveUrl_withBracketNotationInDependencyExtraction() {
        // This test will reveal if dependency extraction handles bracket notation
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(List.of(Map.of("id", "1")));
        map.put("users", usersResponse);

        // URL with bracket notation: ${users[0].id}
        // Dependency should be extracted as "users", not "users[0"
        subRequest.setUrl("http://test.com/api/${users[0].id}");
        
        // First check dependency extraction
        Set<String> dependencies = subRequest.getDependencies();
        assertTrue(dependencies.contains("users"), 
            "Dependency should be 'users', not 'users[0'. Got: " + dependencies);
        
        // Then test resolution
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/1", resolvedUrl);
    }

    @Test
    void testResolveHeaders_withBracketNotation() {
        createStubs();
        Map<String, SubResponse> subResponseMap = new HashMap<>();
        SubResponse configResponse = new SubResponse();
        Map<String, Object> configBody = new HashMap<>();
        configBody.put("api-key", "secret123");
        configResponse.setBody(configBody);
        subResponseMap.put("config", configResponse);
        when(tracker.getSubResponseMap()).thenReturn(subResponseMap);
        when(responseStore.get(batchId)).thenReturn(tracker);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-API-Key", "${config['api-key']}");
        subRequest.setHeaders(headers);
        
        referenceResolverService.resolveHeaders(subRequest, batchId);
        assertEquals("secret123", subRequest.getResolvedHeaders().get("X-API-Key"));
    }

    @Test
    void testResolveUrl_withNullResponseBody_throwsException() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        userResponse.setBody(null);  // Null body
        map.put("user", userResponse);

        subRequest.setUrl("http://test.com/api/${user.name}");
        assertThrows(ReferenceResolutionException.class, () ->
            referenceResolverService.resolveUrl(subRequest, batchId));
    }

    @Test
    void testResolveUrl_withMapResponse() {
        Map<String, SubResponse> map = createStubs();
        SubResponse dataResponse = new SubResponse();
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("id", "123");
        dataResponse.setBody(dataBody);
        map.put("data", dataResponse);

        subRequest.setUrl("http://test.com/api/${data.id}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/123", resolvedUrl);
    }

    @Test
    void testResolveUrl_withCollectionAsRoot() {
        Map<String, SubResponse> map = createStubs();

        SubResponse itemsResponse = new SubResponse();
        itemsResponse.setBody(java.util.Arrays.asList("item1", "item2", "item3"));
        map.put("items", itemsResponse);

        subRequest.setUrl("http://test.com/api/${items[0]}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertEquals("http://test.com/api/item1", resolvedUrl);
    }
}