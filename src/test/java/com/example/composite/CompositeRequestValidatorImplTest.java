package com.example.composite;

import com.example.composite.config.EndpointRegistry;
import com.example.composite.config.EndpointRegistry.EndpointInfo;
import com.example.composite.model.request.CompositeRequest;
import com.example.composite.model.request.SubRequest;
import com.example.composite.model.request.SubRequestDto;
import com.example.composite.service.CompositeRequestValidatorImpl;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CompositeRequestValidatorImplTest {

    private Validator validator;
    private EndpointRegistry endpointRegistry;
    private CompositeRequestValidatorImpl validatorImpl;

    @BeforeEach
    void setUp() {
        validator = mock(Validator.class);
        endpointRegistry = mock(EndpointRegistry.class);
        validatorImpl = new CompositeRequestValidatorImpl(validator, endpointRegistry);
    }

    @Test
    void validateRequest_returnsNoErrors_forValidRequest() {
        CompositeRequest request = new CompositeRequest();
        SubRequestDto sub1 = new SubRequestDto("/ref1", "GET", "/api/ok/get", null, null);
        SubRequestDto sub2 = new SubRequestDto("/ref2", "POST", "/api/ok/post", "{}", Map.of("ref1", "ref1"));
        request.setSubRequests(List.of(sub1, sub2));

        when(validator.validate(request)).thenReturn(Collections.emptySet());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateRequest(request);

        assertThat(errors).isEmpty();
    }

    @Test
    void validateRequest_detectsDuplicateReferenceIds() {
        CompositeRequest request = new CompositeRequest();
        SubRequestDto sub1 = new SubRequestDto("/dup", "GET", "/api/ok", null, null);
        SubRequestDto sub2 = new SubRequestDto("/dup", "POST", "/api/ok", "{}", null);
        request.setSubRequests(List.of(sub1, sub2));

        when(validator.validate(request)).thenReturn(Collections.emptySet());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateRequest(request);

        assertThat(errors).anyMatch(e -> e.contains("Duplicate reference ID found"));
    }

    @Test
    void validateRequest_detectsMissingDependencyReference() {
        CompositeRequest request = new CompositeRequest();
        SubRequestDto sub1 = new SubRequestDto("/ref1/${missingRef}", "GET", "/api/ok", null, null);
        request.setSubRequests(List.of(sub1));

        when(validator.validate(request)).thenReturn(Collections.emptySet());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateRequest(request);

        assertThat(errors).anyMatch(e -> e.contains("Missing dependency reference"));
    }

    @Test
    void validateRequest_detectsCircularDependency() {
        CompositeRequest request = new CompositeRequest();
        SubRequestDto sub1 = new SubRequestDto("/a/${/api/ok/2}", "GET", "/api/ok/1", null, null);
        SubRequestDto sub2 = new SubRequestDto("/b/${/api/ok/1}", "GET", "/api/ok/2", null, null);
        request.setSubRequests(List.of(sub1, sub2));

        when(validator.validate(request)).thenReturn(Collections.emptySet());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateRequest(request);

        assertThat(errors).anyMatch(e -> e.contains("Circular dependency detected"));
    }

    @Test
    void validateRequest_detectsMaxDepthExceeded() {
        CompositeRequest request = new CompositeRequest();
        List<SubRequestDto> subs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String ref = "r" + i;
            Set<String> deps = i == 0 ? Set.of() : Set.of("r" + (i - 1));
            subs.add(new SubRequestDto(ref, "GET", "/api/ok", null, null));
        }
        request.setSubRequests(subs);

        when(validator.validate(request)).thenReturn(Collections.emptySet());
        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateRequest(request);

        assertThat(errors).anyMatch(e -> e.contains("Maximum dependency depth exceeded"));
    }

    @Test
    void validateReferences_returnsErrorForInvalidReferenceFormat() {
        SubRequest req = new SubRequest(new SubRequestDto("/ref1", "GET", "/api/${bad-format!}/ok", null, null));
        Set<String> availableRefs = Set.of("ref1");

        List<String> errors = validatorImpl.validateReferences(req, availableRefs);

        assertThat(errors).anyMatch(e -> e.contains("Invalid reference format"));
    }

    @Test
    void validateReferences_returnsErrorForUnavailableReference() {
        SubRequest req = new SubRequest(new SubRequestDto("/ref1/${missingRef}", "GET", "/api/ok", null, null));
        Set<String> availableRefs = Set.of("ref1");

        List<String> errors = validatorImpl.validateReferences(req, availableRefs);

        assertThat(errors).anyMatch(e -> e.contains("Invalid reference"));
    }

    @Test
    void validateEndpointAccess_returnsErrorForUnavailableEndpoint() {
        SubRequestDto sub = new SubRequestDto("ref1", "GET", "/notfound", null, null);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<String> errors = validatorImpl.validateEndpointAccess(sub);

        assertThat(errors).anyMatch(e -> e.contains("Endpoint not available"));
    }

    @Test
    void validateEndpointAccess_returnsErrorForInvalidHttpMethod() {
        SubRequestDto sub = new SubRequestDto("ref1", "FOO", "/api/ok", null, null);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateEndpointAccess(sub);

        assertThat(errors).anyMatch(e -> e.contains("Invalid HTTP method"));
    }

    @Test
    void validateEndpointAccess_returnsErrorForMissingRequestBody() {
        SubRequestDto sub = new SubRequestDto("ref1", "POST", "/api/ok", null, null);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateEndpointAccess(sub);

        assertThat(errors).anyMatch(e -> e.contains("Request body is required"));
    }

    @Test
    void validateEndpointAccess_returnsErrorForRequestBodyNotAllowed() {
        SubRequestDto sub = new SubRequestDto("ref1", "GET", "/api/ok", "body", null);

        when(endpointRegistry.getEndpointInformations(anyString(), anyString()))
                .thenReturn(Optional.of(mock(EndpointInfo.class)));

        List<String> errors = validatorImpl.validateEndpointAccess(sub);

        assertThat(errors).anyMatch(e -> e.contains("Request body is not allowed"));
    }

    @Test
    void validateResolvedUrlFormat_returnsErrorForInvalidUrl() {
        String error = validatorImpl.validateResolvedUrlFormat("ht!tp://bad_url");

        assertThat(error).contains("Invalid URL format");
    }

    @Test
    void validateResolvedUrlFormat_returnsErrorForRelativeUrlWithoutSlash() {
        String error = validatorImpl.validateResolvedUrlFormat("relative/path");

        assertThat(error).contains("URL must be absolute or start with /");
    }

    @Test
    void validateResolvedUrlFormat_returnsNullForValidAbsoluteUrl() {
        String error = validatorImpl.validateResolvedUrlFormat("http://localhost/api");

        assertThat(error).isNull();
    }

    @Test
    void validateResolvedUrlFormat_returnsNullForValidRelativeUrl() {
        String error = validatorImpl.validateResolvedUrlFormat("/api/ok");

        assertThat(error).isNull();
    }
}