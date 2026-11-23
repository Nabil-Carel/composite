package io.github.nabilcarel.composite;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.controller.CompositeController;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncUtils;

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
    void containerCompletion_cancelsARunningBatch() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CompositeRequest compositeRequest = new CompositeRequest();
        CompletableFuture<ResponseEntity<CompositeResponse>> execution = new CompletableFuture<>();
        when(requestService.execute(compositeRequest, request, response)).thenReturn(execution);

        DeferredResult<ResponseEntity<CompositeResponse>> result =
                controller.execute(request, response, compositeRequest);
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
        asyncManager.setAsyncWebRequest(new StandardServletAsyncWebRequest(request, response));
        asyncManager.startDeferredResultProcessing(result);

        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        for (AsyncListener listener : asyncContext.getListeners()) {
            listener.onComplete(new AsyncEvent(asyncContext));
        }

        assertThat(execution).isCancelled();
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
