package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.request.SubRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ========== URL Dependency Extraction ==========

    @Test
    void getDependencies_withDotNotation_extractsRootId() {
        SubRequest request = createSubRequest("/api/orders/${user.id}", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("user");
    }

    @Test
    void getDependencies_withBracketNotation_extractsRootId() {
        SubRequest request = createSubRequest("/api/orders/${users[0].id}", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("users");
    }

    @Test
    void getDependencies_withNoPlaceholders_returnsEmpty() {
        SubRequest request = createSubRequest("/api/users/123", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).isEmpty();
    }

    @Test
    void getDependencies_withMultiplePlaceholders_extractsAll() {
        SubRequest request = createSubRequest("/api/orders/${user.id}/${product.name}", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactlyInAnyOrder("user", "product");
    }

    @Test
    void getDependencies_withBareReference_returnsWholeString() {
        SubRequest request = createSubRequest("/api/orders/${refId}", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("refId");
    }

    @Test
    void getDependencies_withDuplicateReferences_deduplicates() {
        SubRequest request = createSubRequest("/api/${user.id}/orders/${user.name}", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("user");
    }

    // ========== Header Dependency Extraction ==========

    @Test
    void getDependencies_withHeaderPlaceholders_extractsDependencies() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-User-Id", "${user.id}");

        SubRequest request = createSubRequestWithHeaders("/api/orders", "GET", headers);

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("user");
    }

    @Test
    void getDependencies_withHeaderAndUrlPlaceholders_extractsBoth() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Token", "${auth.token}");

        SubRequest request = createSubRequestWithHeaders("/api/orders/${user.id}", "GET", headers);

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactlyInAnyOrder("user", "auth");
    }

    @Test
    void getDependencies_withNoHeaderPlaceholders_doesNotAddDeps() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");

        SubRequest request = createSubRequestWithHeaders("/api/orders", "GET", headers);

        Set<String> deps = request.getDependencies();

        assertThat(deps).isEmpty();
    }

    // ========== Body Dependency Extraction ==========

    @Test
    void getDependencies_withBodyPlaceholderInObject_extractsDependency() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"userId\": \"${user.id}\"}");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("user");
    }

    @Test
    void getDependencies_withBodyPlaceholderInArray_extractsDependency() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"ids\": [\"${user.id}\", \"${product.id}\"]}");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactlyInAnyOrder("user", "product");
    }

    @Test
    void getDependencies_withNestedBodyPlaceholder_extractsDependency() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"nested\": {\"deep\": \"${user.id}\"}}");

        Set<String> deps = request.getDependencies();

        assertThat(deps).containsExactly("user");
    }

    @Test
    void getDependencies_withNullBody_returnsOnlyUrlAndHeaderDeps() {
        SubRequest request = createSubRequest("/api/orders", "GET");

        Set<String> deps = request.getDependencies();

        assertThat(deps).isEmpty();
    }

    // ========== Node References ==========

    @Test
    void initNodeReferences_withObjectBodyPlaceholder_storesNodeReference() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"userId\": \"${user.id}\"}");

        request.initNodeReferences();

        assertThat(request.getNodeReferences()).hasSize(1);
    }

    @Test
    void initNodeReferences_withArrayBodyPlaceholder_storesArrayNodeReference() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"ids\": [\"${user.id}\"]}");

        request.initNodeReferences();

        assertThat(request.getNodeReferences()).hasSize(1);
    }

    @Test
    void getDependencies_withBodyPlaceholder_doesNotPopulateNodeReferences() throws Exception {
        SubRequest request = createSubRequestWithBody("/api/orders", "POST",
                "{\"userId\": \"${user.id}\"}");

        request.getDependencies();

        assertThat(request.getNodeReferences()).isEmpty();
    }

    // ========== Caching ==========

    @Test
    void getDependencies_calledTwice_returnsSameResult() {
        SubRequest request = createSubRequest("/api/orders/${user.id}", "GET");

        Set<String> first = request.getDependencies();
        Set<String> second = request.getDependencies();

        assertThat(first).isSameAs(second);
    }

    // ========== Delegate ==========

    @Test
    void delegatedMethods_returnDtoValues() {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId("ref1")
                .url("/api/test")
                .method("POST")
                .build();
        SubRequest request = new SubRequest(dto);

        assertThat(request.getReferenceId()).isEqualTo("ref1");
        assertThat(request.getUrl()).isEqualTo("/api/test");
        assertThat(request.getMethod()).isEqualTo("POST");
    }

    // ========== Helper Methods ==========

    private SubRequest createSubRequest(String url, String method) {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId("test-ref")
                .url(url)
                .method(method)
                .build();
        return new SubRequest(dto);
    }

    private SubRequest createSubRequestWithHeaders(String url, String method, Map<String, String> headers) {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId("test-ref")
                .url(url)
                .method(method)
                .headers(headers)
                .build();
        return new SubRequest(dto);
    }

    private SubRequest createSubRequestWithBody(String url, String method, String bodyJson) throws Exception {
        SubRequestDto dto = SubRequestDto.builder()
                .referenceId("test-ref")
                .url(url)
                .method(method)
                .body(mapper.readTree(bodyJson))
                .build();
        return new SubRequest(dto);
    }
}
