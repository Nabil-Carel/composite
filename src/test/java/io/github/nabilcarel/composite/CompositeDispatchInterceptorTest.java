package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.CompositeDispatchInterceptor;
import io.github.nabilcarel.composite.internal.DispatchMarker;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class CompositeDispatchInterceptorTest {

    private static final DispatchMarker MARKER = new DispatchMarker();

    private final CompositeDispatchInterceptor interceptor = new CompositeDispatchInterceptor(10, MARKER);
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
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());

        assertThat(interceptor.preHandle(request, response, handler("annotated"))).isTrue();
    }

    @Test
    void loopbackCall_toUnannotatedHandler_isForbidden() throws Exception {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void loopbackCall_toNonControllerHandler_isForbidden() {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static MockHttpServletRequest loopbackRequest() {
        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());
        return loopback;
    }

    @Test
    void handlerLimit_rejectsExcessAndRecoversAfterCompletion() throws Exception {
        CompositeDispatchInterceptor limited = new CompositeDispatchInterceptor(1, MARKER);
        HandlerMethod handler = handler("annotated");
        MockHttpServletRequest first = loopbackRequest();
        MockHttpServletResponse rejected = new MockHttpServletResponse();

        assertThat(limited.preHandle(first, new MockHttpServletResponse(), handler)).isTrue();
        assertThat(limited.preHandle(loopbackRequest(), rejected, handler)).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(503);

        limited.afterCompletion(first, new MockHttpServletResponse(), handler, null);
        limited.afterCompletion(first, new MockHttpServletResponse(), handler, null);

        assertThat(limited.preHandle(loopbackRequest(), new MockHttpServletResponse(), handler)).isTrue();
        assertThat(limited.preHandle(loopbackRequest(), new MockHttpServletResponse(), handler)).isFalse();
    }

    @Test
    void handlerLimit_isReleasedWhenAHandlerGoesAsync() throws Exception {
        CompositeDispatchInterceptor limited = new CompositeDispatchInterceptor(1, MARKER);
        HandlerMethod handler = handler("annotated");
        MockHttpServletRequest first = loopbackRequest();

        limited.preHandle(first, new MockHttpServletResponse(), handler);
        limited.afterConcurrentHandlingStarted(first, new MockHttpServletResponse(), handler);

        assertThat(limited.preHandle(loopbackRequest(), new MockHttpServletResponse(), handler)).isTrue();
    }

    @Test
    void handlerLimit_doesNotCountDirectRequests() throws Exception {
        CompositeDispatchInterceptor limited = new CompositeDispatchInterceptor(1, MARKER);
        HandlerMethod handler = handler("plain");

        assertThat(limited.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler)).isTrue();
        assertThat(limited.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler)).isTrue();
    }

    @Test
    void forgedMarker_isANormalRequest_andUsesNoPermit() throws Exception {
        CompositeDispatchInterceptor limited = new CompositeDispatchInterceptor(1, MARKER);
        MockHttpServletRequest forged = new MockHttpServletRequest();
        forged.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, "true");

        assertThat(limited.preHandle(forged, new MockHttpServletResponse(), handler("plain"))).isTrue();
        assertThat(limited.preHandle(loopbackRequest(), new MockHttpServletResponse(), handler("annotated"))).isTrue();
    }

    @Test
    void errorDispatch_keepsTheOriginalErrorStatus() throws Exception {
        request.addHeader(CompositeDispatchInterceptor.DISPATCH_HEADER, MARKER.value());
        request.setDispatcherType(DispatcherType.ERROR);

        assertThat(interceptor.preHandle(request, response, handler("plain"))).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
