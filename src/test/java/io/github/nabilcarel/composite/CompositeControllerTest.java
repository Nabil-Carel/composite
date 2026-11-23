package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeControllerTest {

    @Mock
    private CompositeRequestService requestService;
    @Mock
    private HttpServletRequest servletRequest;
    @Mock
    private HttpServletResponse servletResponse;

    private CompositeController controller;

    @BeforeEach
    void setUp() {
        controller = new CompositeController(requestService, new CompositeProperties());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_delegatesToRequestService() {
        CompositeRequest compositeRequest = new CompositeRequest();
        CompositeResponse compositeResponse = new CompositeResponse();
        CompletableFuture<ResponseEntity<CompositeResponse>> future =
                CompletableFuture.completedFuture(ResponseEntity.ok(compositeResponse));

        when(requestService.execute(compositeRequest, servletRequest, servletResponse)).thenReturn(future);

        DeferredResult<ResponseEntity<CompositeResponse>> result =
                controller.execute(servletRequest, servletResponse, compositeRequest);

        ResponseEntity<CompositeResponse> response = (ResponseEntity<CompositeResponse>) result.getResult();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(compositeResponse);
        verify(requestService).execute(compositeRequest, servletRequest, servletResponse);
    }

    @Test
    void getAvailableEndpoints_delegatesToRequestService() {
        EndpointRegistry.EndpointInfo info = EndpointRegistry.EndpointInfo.builder()
                .pattern("/api/users").method("GET").build();
        when(requestService.getAvailableEndpoints()).thenReturn(Set.of(info));

        Set<EndpointRegistry.EndpointInfo> result = controller.getAvailableEndpoints();

        assertThat(result).hasSize(1);
        verify(requestService).getAvailableEndpoints();
    }

    @Test
    void getAvailableEndpoints_withNoEndpoints_returnsEmptySet() {
        when(requestService.getAvailableEndpoints()).thenReturn(Set.of());

        Set<EndpointRegistry.EndpointInfo> result = controller.getAvailableEndpoints();

        assertThat(result).isEmpty();
    }
}
