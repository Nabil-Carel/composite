package io.github.nabilcarel.composite.writer;

import java.io.PrintWriter;
import java.io.Writer;

public abstract class DelegatingWriter extends PrintWriter {
    public DelegatingWriter(Writer delegate) {
        super(delegate);
    }
}
