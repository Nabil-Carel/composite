package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.service.CompositeRequestValidatorImpl;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeRequestValidatorImplTest {

    @Mock
    private Validator validator;
    @Mock
    private EndpointRegistry endpointRegistry;
    @Mock
    private EndpointRegistry.EndpointInfo endpointInfo;

    private CompositeRequestValidatorImpl validatorService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CompositeProperties properties = new CompositeProperties();

    @BeforeEach
    void setUp(TestInfo testInfo) {
        properties.setMaxDepth(2);
        properties.setMaxSubRequestsPerComposite(25);
        validatorService = new CompositeRequestValidatorImpl(validator, endpointRegistry, properties);

        if(testInfo.getTags().contains("skipEndpointMocks")) {
            return;
        }

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(java.util.Optional.of(endpointInfo));
    }

    @Test
    void testValidateRequest_withDuplicateReferenceIds() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("ref1")
                .method("GET")
                .url("/api/test1")
                .build(),
            SubRequestDto.builder()
                .referenceId("ref1")  // Duplicate!
                .method("GET")
                .url("/api/test2")
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Duplicate reference ID"));
    }

    @Test
    void testValidateRequest_withCircularDependency() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("a")
                .method("GET")
                .url("/api/a/${b.id}")  // a depends on b
                .build(),
            SubRequestDto.builder()
                .referenceId("b")
                .method("GET")
                .url("/api/b/${a.id}")  // b depends on a (circular!)
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Circular dependency"));
    }

    @Test
    void testValidateRequest_withMissingDependency() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("a")
                .method("GET")
                .url("/api/a/${missing.id}")  // References 'missing' which doesn't exist
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Missing dependency"));
    }

    @Test
    void testValidateRequest_withValidDependencies() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("a")
                .method("GET")
                .url("/api/a")
                .build(),
            SubRequestDto.builder()
                .referenceId("b")
                .method("GET")
                .url("/api/b/${a.id}")  // b depends on a (valid)
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        // Should have no dependency errors
        assertThat(errors).noneMatch(e -> e.contains("Missing dependency"))
            .noneMatch(e -> e.contains("Circular dependency"));
    }

    @Test
    void testValidateRequest_withBracketNotationInDependency() {
        // This test will reveal if dependency extraction handles bracket notation
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("users")
                .method("GET")
                .url("/api/users")
                .build(),
            SubRequestDto.builder()
                .referenceId("order")
                .method("GET")
                .url("/api/order/${users[0].id}")  // Bracket notation
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        // Should extract "users" as dependency, not "users[0"
        assertThat(errors).noneMatch(e -> e.contains("users[0"));
        // Should not have missing dependency error
        assertThat(errors).noneMatch(e -> e.contains("Missing dependency") && e.contains("users"));
    }

    @Test
    void testValidateRequest_withDependencyInHeaders() {
        // This test will reveal if header dependencies are validated
        CompositeRequest request = new CompositeRequest();
            Map<String, String> headers = new java.util.HashMap<>();
            headers.put("X-User-Id", "${user.id}");
            
            request.setSubRequests(java.util.Arrays.asList(
                SubRequestDto.builder()
                    .referenceId("user")
                    .method("GET")
                    .url("/api/user")
                    .build(),
                SubRequestDto.builder()
                    .referenceId("order")
                    .method("GET")
                    .url("/api/order")
                    .headers(headers)  // Header contains dependency
                    .build()
            ));

            when(validator.validate(any())).thenReturn(java.util.Set.of());

            List<String> errors = validatorService.validateRequest(request);
            
            // Should not have missing dependency error for header reference
            assertThat(errors).noneMatch(e -> e.contains("Missing dependency"));
    }

    @Test
    void testValidateRequest_withDeepDependencyChain() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("a")
                .method("GET")
                .url("/api/a")
                .build(),
            SubRequestDto.builder()
                .referenceId("b")
                .method("GET")
                .url("/api/b/${a.id}")
                .build(),
            SubRequestDto.builder()
                .referenceId("c")
                .method("GET")
                .url("/api/c/${b.id}")
                .build(),
            SubRequestDto.builder()
                .referenceId("d")
                .method("GET")
                .url("/api/d/${c.id}")
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Maximum dependency depth");
    }

    @Test
    void testValidateRequest_withExcessiveDepth() {
        // Create a chain that exceeds max depth (default 10)
        java.util.List<SubRequestDto> requests = new java.util.ArrayList<>();
        requests.add(SubRequestDto.builder()
            .referenceId("a0")
            .method("GET")
            .url("/api/a0")
            .build());
        
        // Create chain of 15 requests
        for (int i = 1; i <= 15; i++) {
            requests.add(SubRequestDto.builder()
                .referenceId("a" + i)
                .method("GET")
                .url("/api/a" + i + "/${a" + (i-1) + ".id}")
                .build());
        }

        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(requests);

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        // Should detect excessive depth
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("Maximum dependency depth"));
    }

    @Test
    void testValidateEndpointAccess_withInvalidMethod() {
        SubRequestDto request = SubRequestDto.builder()
            .referenceId("test")
            .method("INVALID")
            .url("/api/test")
            .build();

        List<String> errors = validatorService.validateEndpointAccess(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Invalid HTTP method"));
    }

    @Test
    void testValidateEndpointAccess_withMissingEndpoint() {
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
            .thenReturn(java.util.Optional.empty());

        SubRequestDto request = SubRequestDto.builder()
            .referenceId("test")
            .method("GET")
            .url("/api/nonexistent")
            .build();

        List<String> errors = validatorService.validateEndpointAccess(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Endpoint not available"));
    }

    @Test
    void testValidateEndpointAccess_withRequestBodyForGet() {
        try {
            SubRequestDto request = SubRequestDto.builder()
                .referenceId("test")
                .method("GET")
                .url("/api/test")
                .body(mapper.readTree("{\"data\": \"value\"}"))  // Body for GET (invalid)
                .build();

            List<String> errors = validatorService.validateEndpointAccess(request);
            
            assertThat(errors).isNotEmpty()
                .anySatisfy(e -> assertThat(e).contains("Request body is not allowed"));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }

    @Test
    void testValidateEndpointAccess_withMissingRequestBodyForPost() {
        SubRequestDto request = SubRequestDto.builder()
            .referenceId("test")
            .method("POST")
            .url("/api/test")
            .body(null)  // Missing body for POST (invalid)
            .build();

        List<String> errors = validatorService.validateEndpointAccess(request);
        
        assertThat(errors).isNotEmpty()
            .anySatisfy(e -> assertThat(e).contains("Request body is required"));
    }

    @Test
    @Tag("skipEndpointMocks")
    void testValidateResolvedUrlFormat_withInvalidUrl() {
        String invalidUrl = "not-a-valid-url";
        String error = validatorService.validateResolvedUrlFormat(invalidUrl);
        
        assertThat(error).isNotNull()
            .satisfiesAnyOf(
                e -> assertThat(e).contains("Invalid URL format"),
                e -> assertThat(e).contains("URL must be absolute")
            );
    }

    @Test
    @Tag("skipEndpointMocks")
    void testValidateResolvedUrlFormat_withValidAbsoluteUrl() {
        String validUrl = "http://example.com/api/test";
        String error = validatorService.validateResolvedUrlFormat(validUrl);
        
        assertThat(error).isNull();
    }

    @Test
    @Tag("skipEndpointMocks")
    void testValidateResolvedUrlFormat_withValidRelativeUrl() {
        String validUrl = "/api/test";
        String error = validatorService.validateResolvedUrlFormat(validUrl);
        
        assertThat(error).isNull();
    }

    @Test
    @Tag("skipEndpointMocks")
    void testValidateRequest_withEmptySubRequests() {
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Collections.emptyList());

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        // Empty requests might be valid or invalid depending on business logic
        // This test documents current behavior
        assertThat(errors).isNotNull();
    }

    @Test
    @Tag("skipEndpointMocks")
    void testValidateRequest_whenSubRequestCountExceedsMaximum_returnsError() {
        properties.setMaxSubRequestsPerComposite(3);

        List<SubRequestDto> requests = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            requests.add(SubRequestDto.builder()
                .referenceId("ref" + i)
                .method("GET")
                .url("/api/test" + i)
                .build());
        }
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(requests);

        List<String> errors = validatorService.validateRequest(request);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).contains("Too many sub-requests").contains("4").contains("3");
    }

    @Test
    void testValidateRequest_whenSubRequestCountEqualsMaximum_isAccepted() {
        properties.setMaxSubRequestsPerComposite(3);
        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<SubRequestDto> requests = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            requests.add(SubRequestDto.builder()
                .referenceId("ref" + i)
                .method("GET")
                .url("/api/test" + i)
                .build());
        }
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(requests);

        List<String> errors = validatorService.validateRequest(request);

        assertThat(errors).noneMatch(e -> e.contains("Too many sub-requests"));
    }

    @Test
    void testValidateRequest_withComplexDependencyGraph() {
        // A -> B, A -> C, B -> D, C -> D (DAG, no cycles)
        CompositeRequest request = new CompositeRequest();
        request.setSubRequests(java.util.Arrays.asList(
            SubRequestDto.builder()
                .referenceId("a")
                .method("GET")
                .url("/api/a")
                .build(),
            SubRequestDto.builder()
                .referenceId("b")
                .method("GET")
                .url("/api/b/${a.id}")
                .build(),
            SubRequestDto.builder()
                .referenceId("c")
                .method("GET")
                .url("/api/c/${a.id}")
                .build(),
            SubRequestDto.builder()
                .referenceId("d")
                .method("GET")
                .url("/api/d/${b.id}/${c.id}")  // Depends on both b and c
                .build()
        ));

        when(validator.validate(any())).thenReturn(java.util.Set.of());

        List<String> errors = validatorService.validateRequest(request);
        
        // Should be valid (no cycles)
        assertThat(errors).noneMatch(e -> e.contains("Circular dependency"))
            .noneMatch(e -> e.contains("Missing dependency"));
    }
}
