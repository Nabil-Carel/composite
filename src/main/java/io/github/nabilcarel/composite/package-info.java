/**
 * Composite — a Spring Boot library for batching multiple HTTP sub-requests into a single
 * composite request with inter-request dependency resolution.
 *
 * <h2>Overview</h2>
 * <p>The Composite library allows API clients to bundle several calls into one HTTP
 * request and express data dependencies between them using a simple placeholder syntax:
 * <pre class="code">
 * POST /api/composite/execute
 * {
 *   "subRequests": [
 *     { "referenceId": "user",  "method": "GET",  "url": "/api/users/42" },
 *     { "referenceId": "order", "method": "POST", "url": "/api/orders",
 *       "body": { "userId": "${user.id}", "email": "${user.email}" } }
 *   ]
 * }
 * </pre>
 * <p>The library builds a dependency DAG from the placeholder expressions, executes
 * independent sub-requests in parallel, and chains dependent sub-requests as their
 * upstream dependencies complete.
 *
 * <h2>Getting started</h2>
 * <ol>
 *   <li>Add the library as a dependency. Spring Boot auto-configuration activates it
 *       automatically.</li>
 *   <li>Annotate the controller methods you want to expose to composite clients with
 *       {@link io.github.nabilcarel.composite.annotation.CompositeEndpoint @CompositeEndpoint},
 *       specifying the response body class:
 *       <pre class="code">
 *       &#64;GetMapping("/api/users/{id}")
 *       &#64;CompositeEndpoint(User.class)
 *       public User getUser(&#64;PathVariable Long id) { ... }
 *       </pre>
 *   </li>
 *   <li>Configure headers to forward (e.g. {@code Authorization}) in
 *       {@code application.properties}:
 *       <pre class="code">
 *       composite.security.forwarded-headers=Authorization
 *       </pre>
 *   </li>
 *   <li>POST a {@link io.github.nabilcarel.composite.model.request.CompositeRequest} to
 *       {@code /api/composite/execute} and receive a
 *       {@link io.github.nabilcarel.composite.model.response.CompositeResponse} with all
 *       sub-responses keyed by {@code referenceId}.</li>
 * </ol>
 *
 * <h2>Package structure</h2>
 * <table border="1">
 *   <caption>Package overview</caption>
 *   <tr><th>Package</th><th>Purpose</th></tr>
 *   <tr><td>{@code annotation}</td><td>{@code @CompositeEndpoint} annotation</td></tr>
 *   <tr><td>{@code autoconfigure}</td><td>Spring Boot auto-configuration and health indicator</td></tr>
 *   <tr><td>{@code config}</td><td>Configuration properties and endpoint registry</td></tr>
 *   <tr><td>{@code config.filter}</td><td>Servlet filter that drives the execution pipeline</td></tr>
 *   <tr><td>{@code controller}</td><td>Built-in composite REST controller</td></tr>
 *   <tr><td>{@code exception}</td><td>Library-specific exception hierarchy</td></tr>
 *   <tr><td>{@code model}</td><td>Runtime coordination types (tracker, coordinator)</td></tr>
 *   <tr><td>{@code model.request}</td><td>Request DTOs and servlet request wrapper</td></tr>
 *   <tr><td>{@code model.response}</td><td>Response DTOs and servlet response wrapper</td></tr>
 *   <tr><td>{@code service}</td><td>Service interfaces and implementations</td></tr>
 *   <tr><td>{@code util}</td><td>Shared regex pattern constants</td></tr>
 * </table>
 *
 * @see io.github.nabilcarel.composite.annotation.CompositeEndpoint
 * @see io.github.nabilcarel.composite.config.CompositeProperties
 * @see io.github.nabilcarel.composite.autoconfigure.CompositeAutoConfiguration
 */
package io.github.nabilcarel.composite;
