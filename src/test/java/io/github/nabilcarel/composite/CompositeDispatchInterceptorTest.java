package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class CompositeDispatchInterceptorTest {

    private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    static class Handlers {
        @CompositeEndpoint
        public void annotated() {
        }

        public void plain() {
        }
    }

    private static HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new Handlers(), Handlers.class.getMethod(name));
    }

    @Test
    void directRequest_isNotRestricted() throws Exception {
        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
    }

    @Test
    void loopbackCall_toAnnotatedHandler_isAllowed() throws Exception {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");

        assertThat(interceptor.preHandle(request, response, handler("annotated"))).isTrue();
    }

    @Test
    void loopbackCall_toUnannotatedHandler_isForbidden() throws Exception {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void loopbackCall_toNonControllerHandler_isForbidden() {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void errorDispatch_keepsTheOriginalErrorStatus() throws Exception {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");
        request.setDispatcherType(DispatcherType.ERROR);

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
