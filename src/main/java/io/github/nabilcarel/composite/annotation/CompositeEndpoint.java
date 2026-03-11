package io.github.nabilcarel.composite.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring MVC controller method as eligible for composite execution.
 *
 * <p>Methods annotated with {@code @CompositeEndpoint} are registered in the
 * {@link io.github.nabilcarel.composite.config.EndpointRegistry EndpointRegistry} at
 * application startup. Only endpoints present in the registry may be targeted by sub-requests,
 * forming an explicit allowlist that prevents Server-Side Request Forgery (SSRF) attacks.
 *
 * <p>The required {@link #value()} attribute declares the Java type that the endpoint's
 * response body should be deserialized into. The composite runtime uses this type hint to
 * correctly parse the loopback HTTP response so that downstream sub-requests can reference
 * fields via the {@code ${referenceId.field}} placeholder syntax.
 *
 * <h2>Usage example</h2>
 * <pre class="code">
 * &#64;RestController
 * &#64;RequestMapping("/api/users")
 * public class UserController {
 *
 *     &#64;GetMapping("/{id}")
 *     &#64;CompositeEndpoint(User.class)
 *     public User getUser(&#64;PathVariable Long id) {
 *         return userService.findById(id);
 *     }
 * }
 * </pre>
 *
 * <p>Once registered, a composite client can reference the response of this endpoint as follows:
 * <pre class="code">
 * {
 *   "subRequests": [
 *     { "referenceId": "user",  "method": "GET", "url": "/api/users/42" },
 *     { "referenceId": "order", "method": "POST", "url": "/api/orders",
 *       "body": { "userId": "${user.id}" } }
 *   ]
 * }
 * </pre>
 *
 * @see io.github.nabilcarel.composite.config.EndpointRegistry
 * @since 0.0.1
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CompositeEndpoint {

  /**
   * The expected response body type of the annotated endpoint.
   *
   * <p>The composite runtime deserializes the loopback response into an instance of this class
   * so that downstream sub-requests can navigate its fields via the
   * {@code ${referenceId.propertyPath}} placeholder syntax. Use {@link Void} for endpoints
   * that produce no response body.
   *
   * @return the response body class; never {@code null}
   */
  Class<?> value();
}
