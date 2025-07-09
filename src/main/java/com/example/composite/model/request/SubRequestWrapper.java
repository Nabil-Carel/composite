package com.example.composite.model.request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ReadListener;
import lombok.Getter;
import lombok.Setter;

public class SubRequestWrapper extends HttpServletRequestWrapper {
    private final String method;
    @Setter
    private final String uri;
    private final byte[] body;
    @Getter
    private final Map<String, String> headers;


    public SubRequestWrapper(HttpServletRequest original, SubRequest subRequest, ObjectMapper mapper) {
        super(original);
        this.method = subRequest.getMethod();
        this.uri = subRequest.getResolvedUrl() == null ? subRequest.getUrl() : subRequest.getResolvedUrl();
        this.body = convertBodyToBytes(subRequest.getBody(), mapper);
        this.headers = new HashMap<>(subRequest.getHeaders());
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getRequestURI() {
        return uri;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            private volatile boolean closed = false;

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
                return !closed && byteStream.available() > 0;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private byte[] convertBodyToBytes(Object body, ObjectMapper mapper) {
        if (body == null) {
            return new byte[0];
        }

        try {
            if (body instanceof byte[]) {
                return (byte[]) body;
            }

            if (body instanceof String) {
                return ((String) body).getBytes(StandardCharsets.UTF_8);
            }

               // Convert object to JSON string using ObjectMapper
            String jsonString = mapper.writeValueAsString(body);
            return jsonString.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert body to bytes", e);
        }
    }
}