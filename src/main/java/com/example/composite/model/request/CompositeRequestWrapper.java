package com.example.composite.model.request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

public class CompositeRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] bodyBytes;
    private final ObjectMapper objectMapper;    
    public CompositeRequestWrapper(HttpServletRequest request, ObjectMapper objectMapper) throws IOException {
        super(request);
        this.objectMapper = objectMapper;

        // Read and cache the body
        String bodyString = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        this.bodyBytes = bodyString.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(bodyBytes);
        return new ServletInputStream() {
            private final boolean closed = false;

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

    public CompositeRequest getBody() throws IOException {
        return objectMapper.readValue(bodyBytes, CompositeRequest.class);
    }

    
}
