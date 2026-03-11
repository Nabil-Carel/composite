package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.controller.CompositeController;
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

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
        controller = new CompositeController(requestService);
    }

    @Test
    void execute_delegatesToRequestService() throws ExecutionException, InterruptedException {
        CompositeResponse compositeResponse = new CompositeResponse();
        CompletableFuture<ResponseEntity<CompositeResponse>> future =
                CompletableFuture.completedFuture(ResponseEntity.ok(compositeResponse));

        when(requestService.execute(servletRequest, servletResponse)).thenReturn(future);

        CompletableFuture<ResponseEntity<CompositeResponse>> result =
                controller.execute(servletRequest, servletResponse, null);

        assertThat(result.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.get().getBody()).isSameAs(compositeResponse);
        verify(requestService).execute(servletRequest, servletResponse);
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
