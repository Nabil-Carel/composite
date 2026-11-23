package io.github.nabilcarel.composite;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import static org.assertj.core.api.Assertions.*;
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
        subRequest.setUrl("/api/${user['name']}");
        subRequest.setHeaders(new HashMap<>());
        subRequest.setBody(null);
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
        assertThat(resolvedUrl).isEqualTo("/api/John%20Doe");
        assertThat(subRequest.getResolvedUrl()).isEqualTo("/api/John%20Doe");
    }

    @Test
    void testResolveUrl_withMultiplePlaceholders() {
        createStubs();
        subRequest.setUrl("/api/${user.name}/city/${user.city}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertThat(resolvedUrl).isEqualTo("/api/John%20Doe/city/New%20York");
    }

    @Test
    void testResolveHeaders_withPlaceholder() {
        createStubs();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-User", "${user.name}");
        subRequest.setHeaders(headers);
        referenceResolverService.resolveHeaders(subRequest, batchId);
        assertThat(subRequest.getResolvedHeaders()).containsEntry("X-User", "John Doe");
    }

    @Test
    void testResolveHeaders_noPlaceholders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Static", "static-value");
        subRequest.setHeaders(headers);
        referenceResolverService.resolveHeaders(subRequest, batchId);
        assertThat(subRequest.getResolvedHeaders()).containsEntry("X-Static", "static-value");
    }

    @Test
    void testResolveBody_withNullBody_doesNotThrow() {
        // subRequest has null body (set in setUp) — resolveBody should exit cleanly
        assertThatCode(() -> referenceResolverService.resolveBody(subRequest, batchId))
            .doesNotThrowAnyException();
    }

    @Test
    void testResolveUrl_invalidPlaceholder_throwsException() {
        createStubs();
        subRequest.setUrl("/api/${invalid}");
        assertThatThrownBy(() -> referenceResolverService.resolveUrl(subRequest, batchId))
            .isInstanceOf(ReferenceResolutionException.class)
            .hasMessageContaining("No response found for reference ID");
    }

    @Test
    void testResolveUrl_missingResponseBody_throwsException() {
        createStubs();
        ResponseTracker localTracker = responseStore.get(batchId);
        localTracker.getSubResponseMap().remove("user");
        subRequest.setUrl("/api/${user.name}");
        assertThatThrownBy(() -> referenceResolverService.resolveUrl(subRequest, batchId))
            .isInstanceOf(ReferenceResolutionException.class)
            .hasMessageContaining("No response found for reference ID");
    }

    @Test
    void testResolveUrl_withBracketNotation_arrayIndex() {
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(java.util.Arrays.asList(
            Map.of("id", "1", "name", "Alice"),
            Map.of("id", "2", "name", "Bob")
        ));
        map.put("users", usersResponse);

        subRequest.setUrl("/api/users/${users[0].id}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);

        assertThat(resolvedUrl).isEqualTo("/api/users/1");
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

        subRequest.setUrl("/api/${config['database.host']}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertThat(resolvedUrl).isEqualTo("/api/localhost");
    }

    @Test
    void testResolveUrl_withSpaceInValue_shouldBeUrlEncoded() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("name", "John Doe");  // Space in value
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        subRequest.setUrl("/api/users/${user.name}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);

        assertThat(resolvedUrl).satisfiesAnyOf(
            url -> assertThat(url).contains("John%20Doe"),
            url -> assertThat(url).contains("John+Doe")
        );
    }

    @Test
    void testResolveUrl_withSpecialCharacters_shouldBeUrlEncoded() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        userBody.put("email", "user@example.com");
        userResponse.setBody(userBody);
        map.put("user", userResponse);

        subRequest.setUrl("/api/users/${user.email}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);

        assertThat(resolvedUrl).satisfiesAnyOf(
            url -> assertThat(url).contains("%40"),
            url -> assertThat(url).contains("user@example.com")
        );
    }

    @Test
    void testResolveUrl_withNestedPlaceholders_isRejected() {
        subRequest.setUrl("/api/${${ref.target}.name}");

        assertThatThrownBy(() -> referenceResolverService.resolveUrl(subRequest, batchId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nested references");
    }

    @Test
    void testResolveUrl_withPlaceholderInStoredValue_isNotExpanded() {
        Map<String, SubResponse> map = createStubs();

        SubResponse aResponse = new SubResponse();
        Map<String, Object> aBody = new HashMap<>();
        aBody.put("value", "${user.name}");  // Stored data, not a template
        aResponse.setBody(aBody);
        map.put("a", aResponse);

        subRequest.setUrl("/api/${a.value}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);

        assertThat(resolvedUrl).isEqualTo("/api/%24%7Buser.name%7D");
    }

    @Test
    void testResolveBody_withArrayElementReference() throws JsonProcessingException{
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(java.util.Arrays.asList(
            Map.of("id", "1"),
            Map.of("id", "2")
        ));
        map.put("users", usersResponse);


        JsonNode body = mapper.readTree("{\"userId\": \"${users[0].id}\"}");
        SubRequestDto dto = SubRequestDto.builder()
            .url("/api/test")
            .method("POST")
            .referenceId("test")
            .body(body)
            .build();
        SubRequest request = new SubRequest(dto);

        referenceResolverService.resolveBody(request, batchId);

        assertThat(request.getBody()).isNotNull();
        String resolvedValue = request.getBody().get("userId").asText();
        assertThat(resolvedValue).isEqualTo("1");
    }

    @Test
    void testResolveBody_withNestedObjectReference() throws JsonProcessingException {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        Map<String, Object> userBody = new HashMap<>();
        Map<String, Object> address = new HashMap<>();
        address.put("city", "New York");
        userBody.put("address", address);
        userResponse.setBody(userBody);
        map.put("user", userResponse);

            JsonNode body = mapper.readTree("{\"city\": \"${user.address.city}\"}");
            SubRequestDto dto = SubRequestDto.builder()
                .url("/api/test")
                .method("POST")
                .referenceId("test")
                .body(body)
                .build();
            SubRequest request = new SubRequest(dto);

            referenceResolverService.resolveBody(request, batchId);
            
            String resolvedValue = request.getBody().get("city").asText();
            assertThat(resolvedValue).isEqualTo("New York");
    }

    @Test
    void testResolveUrl_withBracketNotationInDependencyExtraction() {
        Map<String, SubResponse> map = createStubs();

        SubResponse usersResponse = new SubResponse();
        usersResponse.setBody(List.of(Map.of("id", "1")));
        map.put("users", usersResponse);

        subRequest.setUrl("/api/${users[0].id}");

        Set<String> dependencies = subRequest.getDependencies();
        assertThat(dependencies).contains("users")
            .doesNotContain("users[0");

        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertThat(resolvedUrl).isEqualTo("/api/1");
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
        assertThat(subRequest.getResolvedHeaders()).containsEntry("X-API-Key", "secret123");
    }

    @Test
    void testResolveUrl_withNullResponseBody_throwsException() {
        Map<String, SubResponse> map = createStubs();
        SubResponse userResponse = new SubResponse();
        userResponse.setBody(null);  // Null body
        map.put("user", userResponse);

        subRequest.setUrl("/api/${user.name}");
        assertThatThrownBy(() ->
            referenceResolverService.resolveUrl(subRequest, batchId))
            .isInstanceOf(ReferenceResolutionException.class);
    }

    @Test
    void testResolveUrl_withMapResponse() {
        Map<String, SubResponse> map = createStubs();
        SubResponse dataResponse = new SubResponse();
        Map<String, Object> dataBody = new HashMap<>();
        dataBody.put("id", "123");
        dataResponse.setBody(dataBody);
        map.put("data", dataResponse);

        subRequest.setUrl("/api/${data.id}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertThat(resolvedUrl).isEqualTo("/api/123");
    }

    @Test
    void testResolveUrl_withCollectionAsRoot() {
        Map<String, SubResponse> map = createStubs();

        SubResponse itemsResponse = new SubResponse();
        itemsResponse.setBody(java.util.Arrays.asList("item1", "item2", "item3"));
        map.put("items", itemsResponse);

        subRequest.setUrl("/api/${items[0]}");
        String resolvedUrl = referenceResolverService.resolveUrl(subRequest, batchId);
        assertThat(resolvedUrl).isEqualTo("/api/item1");
    }
}