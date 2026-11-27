package io.github.nabilcarel.composite.internal;

import io.github.nabilcarel.composite.config.CompositeProperties;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Limits composite request bodies while they are read, including chunked requests. */
@ControllerAdvice
public class CompositeRequestSizeAdvice extends RequestBodyAdviceAdapter {

    private final CompositeProperties properties;

    public CompositeRequestSizeAdvice(CompositeProperties properties) {
        this.properties = properties;
    }

    /** Signals that a composite request exceeded {@code composite.max-request-size}. */
    public static final class RequestTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        private RequestTooLargeException() {
            super("Composite request is too large");
        }
    }

    @Override
    public boolean supports(@NonNull MethodParameter parameter, @NonNull Type targetType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == CompositeRequest.class;
    }

    @Override
    @NonNull
    public HttpInputMessage beforeBodyRead(@NonNull HttpInputMessage message, @NonNull MethodParameter parameter,
                                           @NonNull Type targetType,
                                           @NonNull Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        long limit = properties.getMaxRequestSize().toBytes();

        if (message.getHeaders().getContentLength() > limit) {
            throw new RequestTooLargeException();
        }

        InputStream body = new LimitedInputStream(message.getBody(), limit);
        return new HttpInputMessage() {
            @Override
            @NonNull
            public InputStream getBody() {
                return body;
            }

            @Override
            @NonNull
            public HttpHeaders getHeaders() {
                return message.getHeaders();
            }
        };
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                consume(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                consume(count);
            }
            return count;
        }

        private void consume(int count) throws RequestTooLargeException {
            remaining -= count;
            if (remaining < 0) {
                throw new RequestTooLargeException();
            }
        }
    }
}
