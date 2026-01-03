package io.github.nabilcarel.composite.exception;


import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ReferenceResolutionException extends RuntimeException {
    private final String reference;
    private final String availableReferences;

    public ReferenceResolutionException(String message, String reference) {
        super(message);
        this.reference = reference;
        this.availableReferences = null;
    }

    public ReferenceResolutionException(String message, String reference, String availableReferences) {
        super(message);
        this.reference = reference;
        this.availableReferences = availableReferences;
    }

    public String getReference() {
        return reference;
    }

    public String getAvailableReferences() {
        return availableReferences;
    }
}
