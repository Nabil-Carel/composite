package io.github.nabilcarel.composite.writer;

import java.io.IOException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

public abstract class DelegatingServletOutputStream extends ServletOutputStream {
    private final ServletOutputStream delegate;

    public DelegatingServletOutputStream(ServletOutputStream delegate) {
        this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
        delegate.write(b);
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        delegate.setWriteListener(writeListener);
    }
}
