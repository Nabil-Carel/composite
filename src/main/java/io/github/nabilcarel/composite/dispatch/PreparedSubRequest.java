package io.github.nabilcarel.composite.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * A validated sub-request that is ready to send.
 *
 * @param referenceId the sub-request reference ID
 * @param method the HTTP method
 * @param url the encoded, application-relative URL, which must not be encoded again
 * @param headers the read-only headers to send
 * @param body the JSON body, or {@code null} if there is none
 * @since 0.1.0
 */
public record PreparedSubRequest(String referenceId, HttpMethod method, String url, HttpHeaders headers,
                                 JsonNode body) {
}
