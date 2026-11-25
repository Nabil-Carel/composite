# Composite Endpoint Library
[View on GitHub](https://github.com/Nabil-Carel/composite)

Batch multiple dependent API calls into a single request — with zero boilerplate.

## Overview

The Composite Endpoint Library for Spring Boot lets you combine multiple REST endpoints into one request, similar to Salesforce's Composite API. It automatically discovers annotated endpoints, executes them in the correct dependency order, and returns a single aggregated response — eliminating the need to manually chain calls in your frontend or create custom aggregation endpoints in your backend.

## Project Status 🚧

This library is under active development.  
The goal is to fully replicate the behavior of Salesforce's Composite API — including dependent request execution, automatic endpoint discovery, and batch orchestration.

## Features

- `@CompositeEndpoint` annotation to opt-in existing endpoints.
- Automatic endpoint registration — no need to repeat paths or change controllers.
- Dependency support — later requests can reference IDs or values from earlier responses.
- Execution ordering based on declared dependencies.
- Works with existing Spring Boot controllers — no extra wiring.
- Aggregated error handling and response structure.
- Full dependency resolution syntax (Salesforce-style reference IDs)
- Automatic authentication forwarding for all subrequests.

## When to Use

This library is ideal for applications that suffer from **chatty API patterns** and need to reduce network round-trips without creating custom aggregation endpoints.

### Perfect Use Cases

**Sequential API Dependencies**
```javascript
// Instead of this frontend waterfall:
const customer = await api.createCustomer(customerData);
const account = await api.createAccount(customer.id, accountData);
const order = await api.createOrder(account.id, orderData);

// Do this in one request:
const result = await api.composite([
  { method: "POST", url: "/customers", body: customerData, referenceId: "cust" },
  { method: "POST", url: "/accounts", body: { ...accountData, customerId: "${cust.id}" }, referenceId: "acc" },
  { method: "POST", url: "/orders", body: { ...orderData, accountId: "${acc.id}" }, referenceId: "order" }
]);
```

**Mobile & High-Latency Networks**
- Your mobile app makes 5+ API calls to render a single screen
- Users on slow connections experience noticeable delays between requests
- You want to minimize battery drain from multiple network requests

**Complex Dashboard Loading**
- Admin dashboards that aggregate data from multiple services
- Reporting interfaces that need data from several related endpoints
- Any UI that requires "loading multiple things at once"

### When NOT to Use

**Simple, Independent Requests**
- If your requests don't depend on each other, regular parallel HTTP calls are simpler
- Single-endpoint operations don't benefit from composite requests

**Heavy Computational Workloads**
- Long-running processes that should be asynchronous
- Operations that benefit from being queued/background processed

**Complex Business Transactions**
- Multistep workflows that need custom rollback logic
- Operations that span multiple databases or external services with specific transaction requirements

### Migration Scenarios

**Replace "Backend for Frontend" Endpoints**
```java
// Instead of creating custom aggregation controllers:
@GetMapping("/dashboard/user/{id}")
public DashboardData getUserDashboard(@PathVariable String id) {
    User user = userService.getUser(id);
    List<Order> orders = orderService.getOrdersByUser(id);
    List<Invoice> invoices = invoiceService.getInvoicesByUser(id);
    return new DashboardData(user, orders, invoices);
}

// Use existing endpoints with composite requests
```

**Eliminate Frontend Request Waterfalls**
- Replace chains of dependent `await` calls with single composite requests
- Reduce frontend complexity by moving orchestration to the backend
- Improve perceived performance with fewer loading states

## Security

✅ **Works with Spring Security out of the box**

The Composite Endpoint Library makes real HTTP loopback calls to your endpoints, which means **all your existing Spring Security configuration applies automatically**.

**How it works:**

- **Authentication Forwarding**: `Authorization` and `Cookie` are forwarded from the parent composite request to all subrequests by default. Change the list with `composite.security.forwarded-headers`
- **Authorization**: Each subrequest goes through your Spring Security filter chain exactly as a direct HTTP call would
- **Method Security**: `@PreAuthorize`, `@Secured`, and other method-level annotations work normally
- **No Privilege Escalation**: Composite requests can only access endpoints the calling user is authorized to access

**Supported authentication types:**
- ✅ Bearer tokens (JWT, OAuth2)
- ✅ Basic Authentication
- ✅ Session cookies (JSESSIONID)
- ✅ API keys in headers
- ✅ Any custom header-based auth

**Example:**
```java
@GetMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@CompositeEndpoint
public List<User> getUsers() {
    // Only accessible to admins
}
```

If a non-admin tries to include this endpoint in a composite request, that specific subrequest will fail with 403 Forbidden, while other authorized subrequests succeed.

**Security headers:**

The library automatically injects tracking headers to identify composite-initiated requests:
- `X-Composite-Request: true`
- `X-Composite-Request-Id: <unique-id>`
- `X-Composite-Sub-Request-Id: <reference-id>`

Use these for auditing and logging only. A direct client can send the same header names, so they must not be used for authorization.

**Sub-request safety rules:**

- Targets must be application-relative paths. Absolute URLs, `//host`, dot segments, fragments and path parameters are rejected.
- A sub-request runs only if the handler Spring selects has `@CompositeEndpoint`. This also applies to handlers from other handler mappings, such as Actuator endpoints on the main port, which are refused with `403`.
- Clients can set only headers in `composite.security.client-allowed-headers`. Credential, proxy, identity and hop-by-hop headers are always refused, and so is any header on `composite.security.forwarded-headers`, so a caller cannot swap in another credential for one sub-request.
- Forwarded headers are copied exactly as the caller sent them. Forward only credentials. If your app trusts a header such as `X-Forwarded-For` because a proxy sets it, forwarding it lets callers' values through unchecked.
- Response values are substituted once, so stored data cannot act as a `${...}` reference.
- Applications using cookie authentication must keep CSRF protection on the composite endpoint and add their CSRF header to `composite.security.forwarded-headers`.
- The caller stays authenticated for the async batch response when the app uses Spring Security's default security context repositories. A custom `SecurityContextRepository` must support async dispatches.
- A gateway only sees the outer composite URL. Protect annotated endpoints with application-level authorization rather than relying only on gateway path rules.
- Sub-requests arrive from localhost, and one batch can create up to 25 inner requests by default. Account for this in IP rules and rate limits.
- At `INFO` and above, Composite logs reference IDs, statuses and failure types, never sub-request URLs, headers or response bodies. Rejected requests and error responses are logged at `DEBUG`, where validation messages can include the submitted URL.

## Installation

⚠️ **Work In Progress**

This library is currently not published to a public Maven repository.
To try it out, you need to build and install the artifact locally or include the source directly in your project.

Official release and simplified installation instructions will be provided in a future version.

## Usage

### 1. Annotate your endpoints
```java
@RestController
@RequestMapping("/api")
public class AccountController {

    @GetMapping("/account/{id}")
    @CompositeEndpoint
    public Account getAccount(@PathVariable String id) {
        // Your business logic
    }
}
```
References read the JSON response, so `${acc1.user_id}` uses the field name a client sees and cannot reach fields hidden with `@JsonIgnore`.

### 2. Send a composite request with dependencies
```http
POST /api/composite/execute
Authorization: Bearer <your-token>
Content-Type: application/json

{
  "subRequests": [
    {
      "method": "GET",
      "url": "/api/account/1",
      "referenceId": "acc1"
    },
    {
      "method": "GET",
      "url": "/api/orders/${acc1.id}",
      "referenceId": "order1"
    }
  ]
}
```

### 3. Receive aggregated results
```json
{
  "hasErrors": false,
  "responses": {
    "acc1": {
      "referenceId": "acc1",
      "httpStatus": 200,
      "body": { "id": "1", "name": "John Doe" }
    },
    "order1": {
      "referenceId": "order1",
      "httpStatus": 200,
      "body": [ { "orderId": "A123" }, { "orderId": "B456" } ]
    }
  }
}
```

### 4. Configure it if needed
```properties
composite.base-path=/api/composite
composite.controller.enabled=true
composite.request-timeout=60s
composite.max-sub-requests-per-composite=25
composite.max-concurrent-sub-requests=5
composite.max-depth=10
composite.security.forwarded-headers=Authorization,Cookie
composite.security.client-allowed-headers=Accept,Accept-Language,If-Match,If-None-Match,If-Modified-Since,If-Unmodified-Since
composite.security.blocked-headers=X-User-Id
composite.header-injection.enabled=true
# jdk (default, no extra dependency) or webclient (requires spring-boot-starter-webflux)
composite.loopback.client=jdk
composite.loopback.max-response-size=256KB
# Defaults to half of server.tomcat.threads.max, or half of server.tomcat.max-connections with virtual threads
composite.loopback.max-connections=100
composite.max-resolved-size=1MB
composite.max-request-size=2MB
```

If `composite.request-timeout` elapses, the response is `504`, in-flight calls are cancelled and unstarted sub-requests do not run. Embedded Tomcat does not report a client disconnect while a batch is waiting, so a disconnected batch continues until it finishes or reaches this timeout. A handler that is already running cannot be stopped, so use idempotency keys on non-idempotent endpoints.

Sub-requests are sent with the non-blocking JDK `HttpClient` by default, so Composite needs no Netty or Reactor Netty. Set `composite.loopback.client=webclient` and add `spring-boot-starter-webflux` to use WebClient instead. Both clients wait for responses without holding a thread.

With `composite.loopback.protocol=https`, loopback calls verify the server certificate against the JVM truststore. `composite.loopback.trust-self-signed-certificates=true` skips that check entirely and is meant for development only. In production, add your certificate to the truststore instead.

`composite.loopback.max-connections` also limits running sub-request handlers across all batches. Above it, calls wait for a connection, or return `503` if timed-out handlers are still running. The limit is shared, so one client sending many slow batches can delay other batch users. Add per-client rate limiting in production.

Unknown request fields are rejected with `400`. Batches are not transactional, so a failed sub-request does not undo earlier ones. A sub-request whose dependency returned anything other than `2xx`, including a redirect or `304`, does not run and returns `424`.

### 5. Stop the batch on the first failure
```json
{
  "stopOnFailure": true,
  "subRequests": [
    { "method": "POST", "url": "/api/orders", "referenceId": "order", "body": { "sku": "A1" } },
    { "method": "POST", "url": "/api/payments", "referenceId": "payment", "body": { "orderId": "${order.id}" } }
  ]
}
```

With `stopOnFailure`, the first non-2xx sub-response stops the rest of the batch:

- Sub-requests that never started return `424`.
- In-flight calls are cancelled and return `503`. Their handlers may have already run, so treat their outcome as unknown.
- Completed work is **not** rolled back. Use one transactional endpoint when an operation must be all-or-nothing.

### 6. Replace an implementation

Every default service can be replaced by declaring a bean of its interface. To change how sub-requests are sent, implement `SubRequestDispatcher`:

```java
@Bean
SubRequestDispatcher customDispatcher() {
    return (request, cancellation) -> sendToThisApplication(request, cancellation);
}
```

A dispatcher must only call the current application and must not follow redirects.

### 7. Observe it with Java Flight Recorder

The library emits `io.github.nabilcarel.composite.Batch` and `io.github.nabilcarel.composite.SubRequest` JFR events. They include timing, status, outcome and the route pattern, but never resolved URLs, headers or bodies.

```bash
java -XX:StartFlightRecording=filename=composite.jfr,settings=profile -jar your-app.jar
```

## How It Works

The Composite Endpoint Library acts as an intelligent request orchestrator that coordinates multiple HTTP calls to your existing endpoints.

### Request Processing Flow

1. **Request Acceptance**: Spring MVC authenticates, negotiates and binds the request to `/api/composite/execute`
2. **Validation**: Validates the composite request structure and checks for circular dependencies
3. **Dependency Analysis**: Parses subrequests and builds a dependency graph
4. **Parallel Execution**: Independent requests execute in parallel through a non-blocking HTTP client
5. **Loopback Calls**: Each subrequest makes an HTTP call back to `localhost` hitting your actual endpoints
6. **Response Tracking**: Results are captured and stored, making them available for dependent requests
7. **Reference Resolution**: Subsequent requests can reference earlier results using `${refId.property}` syntax
8. **Aggregated Response**: All results are combined into a single JSON response

### Why This Approach Works

**Full Spring Security integration**: Each subrequest is a real HTTP call that goes through your complete Spring Security filter chain - authentication, authorization, method security, and custom filters all work exactly as they do for direct requests.

**Debugging is straightforward**: Since each subrequest hits your actual controller methods via HTTP, you can set breakpoints, view stack traces, and use all your normal debugging tools.

**All Spring features work**: Validation annotations, exception handlers, request interceptors, and any other Spring MVC features function normally because each subrequest goes through Spring's complete request lifecycle.

**No business logic changes**: Your existing controllers remain unchanged. The library only adds orchestration and response aggregation.

**Dependency resolution is simple**: When a request references `${acc1.id}`, the library looks up `acc1` in the results map and reads `id` from its JSON response.

### Request Flow Example
```
POST /api/composite/execute
Authorization: Bearer <token>

{
  "subRequests": [
    { "method": "GET", "url": "/api/account/123", "referenceId": "acc1" },
    { "method": "GET", "url": "/api/orders/${acc1.id}", "referenceId": "orders1" }
  ]
}
```

**What happens internally:**

1. The composite controller receives the accepted request and validates its structure
2. First subrequest: the loopback client makes GET to `http://localhost:8080/api/account/123`
    - Authorization header automatically forwarded from parent request
    - Request goes through full Spring Security filter chain
    - Hits `AccountController.getAccount(123)`
    - Response captured: `{"id": "123", "name": "John Doe"}`
    - Stored in results map as `acc1`
3. Second subrequest: `${acc1.id}` resolved to `123`
    - URL becomes `/api/orders/123`
    - The loopback client makes GET to `http://localhost:8080/api/orders/123`
    - Authorization header forwarded again
    - Goes through Spring Security again
    - Hits `OrderController.getOrders(123)`
    - Response captured and stored as `orders1`
4. Both results returned in aggregated response

### Benefits of This Design

- **True HTTP calls**: Each subrequest is a real HTTP call with full Spring lifecycle
- **Security out of the box**: Spring Security filters apply to every subrequest automatically
- **Full Spring compatibility**: Every Spring feature works exactly as expected
- **Easy testing**: Each endpoint can be tested independently as regular HTTP endpoints
- **Simple debugging**: Set breakpoints in your actual controller methods
- **Request tracing**: Standard HTTP tracing/monitoring tools work on subrequests
- **Header injection**: Library adds tracking headers to identify composite-initiated requests

## Frontend Benefits

1. **Fewer HTTP Calls, Lower Latency:** Send one composite request instead of many.
2. **Simplified Frontend Logic:** Declare all needs in one request.
3. **Frontend-Driven Composition:** Compose existing endpoints as needed.
4. **Cleaner State Management:** Receive all required data in one snapshot.

## Backend Benefits

1. **No More "Backend for Frontend" Hell:** No need for custom aggregation endpoints.
2. **Promotes Clean API Design:** Encourages small, focused, reusable endpoints.
3. **Separation of Concerns:** Orchestration is handled by the library.
4. **No Coordination Debt:** Decouple frontend and backend release cycles.
5. **Code Reuse and Performance Gains:** Identical calls can be reused within a composite request.
6. **Easy to Adopt:** Add composite support to existing endpoints with a single `@CompositeEndpoint` annotation - no refactoring required.

## FAQ

### Why not just use GraphQL?

GraphQL requires rewriting your entire API surface with schemas and resolvers. This library works with your existing REST endpoints - just add one annotation. No learning curve for teams already comfortable with REST, and no migration effort for existing controllers.

### Why not write service layer methods instead?

Service layers create backend-specific aggregations for single use cases. Tomorrow your frontend needs a different data combination, so you write another service method. Then another. This library lets the **frontend decide** what data combinations it needs without requiring backend changes - no more anticipating every possible data composition.

### What about Salesforce's Composite API?

This **is** Salesforce's approach, adapted for Spring Boot. You don't need a Salesforce license or ecosystem - it works with your existing Spring controllers using familiar Spring patterns.

### When should I NOT use this?

- **Simple, independent requests** that don't depend on each other - regular parallel HTTP calls are simpler
- **Long-running processes** that should be asynchronous or queued
**Complex Business Transactions**
- Operations that require all-or-nothing semantics (if one fails, all must rollback)
- Distributed transactions across multiple databases
- Workflows that need compensating transactions

**Note:** Individual endpoints can use `@Transactional` and will rollback on failure, but the composite request itself doesn't provide cross-endpoint transaction management. If subrequest 1 succeeds and subrequest 2 fails, subrequest 1's changes are NOT automatically rolled back.

### Can I nest composite requests?

No, composite requests cannot include calls to `/api/composite/execute` to prevent infinite recursion.

### Is there a limit on batch size?

Yes, the default maximum is 25 sub-requests per composite call (matching Salesforce's limit). This prevents resource exhaustion and can be configured via `composite.max-sub-requests-per-composite`.

### How does this handle request/response size limits?

Composite requests are subject to normal Spring Boot request size limits (`server.servlet.max-request-size`). Large batches with big payloads may need to be split into smaller composite requests to stay within these limits. Each sub-response is limited to 256 KB by default. A larger one fails with `503` unless you raise `composite.loopback.max-response-size`.

### Does this work with Spring WebFlux or other Java frameworks?

No, this library is designed specifically for Spring Boot applications using Spring Web MVC (`spring-boot-starter-web`). The library requires servlet-based request handling. Pure Spring WebFlux (reactive) applications are not currently supported.

### What happens if one request in the batch fails?

You get partial results with detailed status information for each sub-request. Failed requests don't cause the entire batch to fail - you receive all successful results along with error details for any failures. There's no automatic rollback.

## License

Apache License 2.0