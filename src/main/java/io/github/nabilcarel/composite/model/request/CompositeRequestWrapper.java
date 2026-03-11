package io.github.nabilcarel.composite.model.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@link HttpServletRequestWrapper} that caches the raw request body so it can be read
 * more than once.
 *
 * <p>The standard Servlet API allows the body {@link jakarta.servlet.ServletInputStream} to
 * be consumed only once. The composite filter reads the body to deserialise the
 * {@link CompositeRequest}, and the downstream controller also needs to read it (e.g. for
 * API documentation introspection). This wrapper eagerly reads and caches the bytes in its
 * constructor, then returns a fresh {@link java.io.ByteArrayInputStream} from every call to
 * {@link #getInputStream()}.
 *
 * @see io.github.nabilcarel.composite.config.filter.CompositeRequestFilter
 * @since 0.0.1
 */
public class CompositeRequestWrapper extends HttpServletRequestWrapper {

  /** The cached raw bytes of the request body. */
  private final byte[] bodyBytes;

  private final ObjectMapper objectMapper;

  public CompositeRequestWrapper(HttpServletRequest request, ObjectMapper objectMapper)
      throws IOException {
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
