package io.github.nabilcarel.composite;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.CompositeRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeRequestWrapperTest {

    @Mock
    private HttpServletRequest servletRequest;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void getBody_deserializesJsonToCompositeRequest() throws IOException {
        String json = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        CompositeRequest body = wrapper.getBody();

        assertThat(body).isNotNull();
        assertThat(body.getSubRequests()).hasSize(1);
        assertThat(body.getSubRequests().get(0).getReferenceId()).isEqualTo("a");
        assertThat(body.getSubRequests().get(0).getMethod()).isEqualTo("GET");
        assertThat(body.getSubRequests().get(0).getUrl()).isEqualTo("/api/test");
    }

    @Test
    void getInputStream_returnsRereadableStream() throws IOException {
        String json = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);

        // Read the stream twice — should return the same content
        byte[] firstRead = wrapper.getInputStream().readAllBytes();
        byte[] secondRead = wrapper.getInputStream().readAllBytes();

        assertThat(new String(firstRead, StandardCharsets.UTF_8)).isEqualTo(json);
        assertThat(new String(secondRead, StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void getInputStream_isFinished_returnsTrueAfterFullRead() throws IOException {
        String json = "{\"subRequests\":[]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        ServletInputStream stream = wrapper.getInputStream();

        // Read all bytes
        stream.readAllBytes();

        assertThat(stream.isFinished()).isTrue();
    }

    @Test
    void getInputStream_isReady_returnsTrueBeforeRead() throws IOException {
        String json = "{\"subRequests\":[]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        ServletInputStream stream = wrapper.getInputStream();

        assertThat(stream.isReady()).isTrue();
    }

    @Test
    void getInputStream_setReadListener_throwsUnsupportedOperationException() throws IOException {
        String json = "{\"subRequests\":[]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        ServletInputStream stream = wrapper.getInputStream();

        assertThatThrownBy(() -> stream.setReadListener(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getBody_calledMultipleTimes_returnsFreshInstance() throws IOException {
        String json = "{\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/test\"}]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        CompositeRequest first = wrapper.getBody();
        CompositeRequest second = wrapper.getBody();

        assertThat(first).isNotSameAs(second);
        assertThat(first.getSubRequests()).hasSize(1);
        assertThat(second.getSubRequests()).hasSize(1);
    }

    @Test
    void getBody_withMultipleSubRequests_deserializesAll() throws IOException {
        String json = "{\"subRequests\":[" +
                "{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/a\"}," +
                "{\"referenceId\":\"b\",\"method\":\"POST\",\"url\":\"/api/b\"}" +
                "]}";
        setupRequest(json);

        CompositeRequestWrapper wrapper = new CompositeRequestWrapper(servletRequest, objectMapper);
        CompositeRequest body = wrapper.getBody();

        assertThat(body.getSubRequests()).hasSize(2);
        assertThat(body.getSubRequests().get(0).getReferenceId()).isEqualTo("a");
        assertThat(body.getSubRequests().get(1).getReferenceId()).isEqualTo("b");
    }

    // ========== Helper Methods ==========

    private void setupRequest(String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream byteStream = new ByteArrayInputStream(bodyBytes);

        ServletInputStream servletInputStream = new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return byteStream.read();
            }

            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
            }
        };

        when(servletRequest.getInputStream()).thenReturn(servletInputStream);
    }
}
