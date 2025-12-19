package io.github.nabilcarel.composite.model.response;

import jakarta.servlet.http.HttpServletResponseWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
public class SubResponseWrapper extends HttpServletResponseWrapper {
    @Getter
    private final Class<?> responseType;
    private final ObjectMapper objectMapper;
    private final CountDownLatch latch = new CountDownLatch(1);
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final PrintWriter writer = new PrintWriter(buffer);
    @Getter
    private final String reference;


    public SubResponseWrapper(HttpServletResponse response, Class<?> responseType, String reference, ObjectMapper objectMapper) {
        super(response);
        this.responseType = responseType;
        this.reference = reference;
        this.objectMapper = objectMapper;
    }

    public Map<String, String> getHeadersAsMap() {
        return super.getHeaderNames().stream()
                .collect(Collectors.toMap(Function.identity(), super::getHeader));
    }


    @Override
    public PrintWriter getWriter() throws IOException {
    return writer;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        return new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                buffer.write(b);
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
               // do nothing
            }
        };
    }

    @Override
    public void flushBuffer() {
        // Override to prevent committing the response
    }

    @Override
    public void sendError(int sc) {
        // Prevent errors from committing
    }

    @Override
    public void sendError(int sc, String msg) {
        // Prevent errors from committing
    }

    @Override
    public void sendRedirect(String location) {
        // Prevent redirects
    }

    @Override
    public void setStatus(int sc) {
        // Override to prevent setting a status that could commit the response
    }

    public String getCapturedResponseBody() {
        writer.flush(); // Ensure all data is written to the buffer
        return buffer.toString(); // Convert response body to string
    }
}