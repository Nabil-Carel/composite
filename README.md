# Composite Endpoint Library
[View on GitHub](https://github.com/Nabil-Carel/composite)

Batch multiple dependent API calls into a single request — with zero boilerplate.

## Overview

The Composite Endpoint Library for Spring Boot lets you combine multiple REST endpoints into one request, similar to Salesforce's Composite API. You mark existing endpoints with `@CompositeEndpoint`, and clients can then send a batch of calls in which later calls use values from earlier responses. The library runs them in dependency order, in parallel where possible, and returns one aggregated response. You don't need to chain calls in the frontend or write custom aggregation endpoints in the backend.

## Project Status 🚧

Version `0.1.0` is under active development and not yet published to Maven Central. The public API may still change before `1.0`.

## Features

- `@CompositeEndpoint` opts existing endpoints in. Nothing else is reachable through a batch.
- Endpoints are discovered automatically, so you don't repeat paths or change controllers.
- Later sub-requests can reference values from earlier responses with `${referenceId.path}`, in the URL, headers or body.
- Dependencies are inferred from those references. Independent sub-requests run in parallel.
- Each sub-request is a real HTTP call to your application, so Spring Security, validation, exception handlers and interceptors all apply.
- Authentication headers are forwarded from the outer request.
- `stopOnFailure` stops the rest of a batch after the first failure.
- Limits on batch size, depth, concurrency, request and response size, referenced data and time.
- The default HTTP client is the JDK's non-blocking `HttpClient`, with an optional WebClient. Both work with virtual threads.
- Java Flight Recorder events and an Actuator health indicator.
- Extension points: replace how sub-requests are sent, or expose batches from your own controller.

## When to Use

This library suits applications with **chatty API patterns** that need fewer network round-trips without custom aggregation endpoints.

### Good Use Cases

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
- Users on slow connections notice the delay between requests
- You want to reduce battery drain from many network requests

**Complex Dashboard Loading**
- Admin dashboards that combine data from several endpoints
- Reporting screens that need data from related resources
- Any UI that loads many things at once

### When NOT to Use

- **Simple, independent requests:** if calls don't depend on each other, regular parallel HTTP calls are simpler.
- **Long-running work:** processes that should be asynchronous, queued or run in the background.
- **All-or-nothing transactions:** batches are not transactional. See [What happens if one request in the batch fails?](#what-happens-if-one-request-in-the-batch-fails)

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
- Replace chains of dependent `await` calls with one composite request
- Move orchestration from the frontend to the backend
- Show fewer loading states

## Installation

The library is not yet published to a public Maven repository. Build it and install it into your local Maven repository:

```bash
git clone https://github.com/Nabil-Carel/composite.git
cd composite
./gradlew publishToMavenLocal
```

Then add it to your application:

```groovy
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.nabil-carel:composite-spring-boot-starter:0.1.0'
}
```

**Requirements:**
- Java 17 or later
- Spring Boot 3 with Spring MVC (`spring-boot-starter-web`)
- Spring MVC's default path matching. An application that sets `spring.mvc.pathmatch.matching-strategy=ant_path_matcher` fails at startup with a message explaining the setting.

The library brings `spring-boot-starter-web`, `spring-boot-starter-validation` and `reactor-core`. WebFlux is only needed if you choose the WebClient option.

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

### 2. Send a composite request
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

## Writing Requests

### Sub-requests

Each sub-request has these fields:

| Field | Required | Description |
|---|---|---|
| `referenceId` | yes | Unique name in the batch: letters, numbers, `-` or `_`, up to 50 characters. |
| `method` | yes | `GET`, `POST`, `PUT`, `PATCH` or `DELETE`, in uppercase. |
| `url` | yes | An application-relative path such as `/api/users/1`, optionally with a query string. |
| `body` | no | Any JSON value. It is sent for every method except `GET`. |
| `headers` | no | Extra headers, limited to `composite.security.client-allowed-headers`. |

The batch itself accepts `subRequests` and `stopOnFailure`. Unknown fields are rejected with `400`.

Each sub-response has `referenceId`, `httpStatus` and `body`. JSON bodies, including JSON error bodies such as problem details, are returned as JSON, and other bodies as text. Response headers listed in `composite.response-headers`, `Location` and `ETag` by default, appear in `headers`, which is left out when there are none:

```json
"created": {
  "referenceId": "created",
  "httpStatus": 201,
  "body": { "id": "42" },
  "headers": { "Location": "/api/orders/42" }
}
```

### References

`${referenceId.path}` reads a value from an earlier sub-request's JSON response. A sub-request that uses a reference waits for the sub-request it names, so you never declare dependencies separately.

- **Dot keys:** `${user.address.city}`
- **Array indexes:** `${order.items[0].sku}`
- **Bracket keys,** for names containing dots or other characters: `${config['db.host']}`, `${config["db.host"]}` or `${user[name]}`

References work in the URL, in header values and in the body:

- A body value that is exactly one reference keeps its JSON type, so `"items": "${cart.items}"` inserts the array, and `"count": "${cart.size}"` inserts a number.
- Inside larger text, such as `"Order ${order.id} for ${user.name}"`, or in a URL or header, a reference must point to a string, number or boolean.
- Values inserted into a URL are encoded, so they can't add path segments or query parameters. A value is checked against the route again after it's inserted, including regex constraints such as `{id:\d+}`.
- Inserted values are never read as references again, so stored data can't trigger further substitution.

References use the JSON the endpoint returns, so `${acc1.user_id}` uses the field name a client sees and can't reach fields hidden with `@JsonIgnore`. Referencing a missing or `null` value fails that sub-request with `400`.

### Response format

| Field | Description |
|---|---|
| `responses` | One entry per sub-request, by `referenceId`, with its `httpStatus` and `body`. JSON bodies are returned as JSON. Other bodies, and error messages from Composite, are strings. |
| `hasErrors` | `true` if any sub-request returned a status outside `2xx`. |
| `errors` | Batch-level problems, such as validation errors or a timeout. |
| `debug` | The dependency graph and resolved requests, only with `composite.debug-enabled=true`. |

The status of the outer response tells you how the batch ended:

| Status | Meaning |
|---|---|
| `200` | Every sub-request has a result. Check `hasErrors` and each `httpStatus`. |
| `400` | The batch was invalid and nothing ran. `errors` explains each problem. |
| `413` | The batch was larger than `composite.max-request-size`. Nothing ran. |
| `504` | `composite.request-timeout` elapsed. Completed results are included, unstarted sub-requests did not run, and in-flight calls were cancelled. |

Composite uses these statuses for sub-requests that didn't produce their own response:

| Status | Meaning |
|---|---|
| `400` | The sub-request couldn't be prepared: for example, an unresolvable reference, or a header value outside ISO-8859-1. |
| `403` | The selected handler doesn't have `@CompositeEndpoint`. |
| `424` | Not run, because a dependency returned a status outside `2xx` (including a redirect or `304`), or because `stopOnFailure` stopped the batch. |
| `502` | The endpoint said it returned JSON, but the body wasn't valid JSON. |
| `503` | The call failed or was cancelled. The handler may already have run. Also returned when a sub-response is larger than `composite.loopback.max-response-size`, or when too many sub-requests are running. |

### Stop the batch on the first failure
```json
{
  "stopOnFailure": true,
  "subRequests": [
    { "method": "POST", "url": "/api/orders", "referenceId": "order", "body": { "sku": "A1" } },
    { "method": "POST", "url": "/api/payments", "referenceId": "payment", "body": { "orderId": "${order.id}" } }
  ]
}
```

With `stopOnFailure`, the first sub-response outside `2xx` stops the rest of the batch:

- Sub-requests that never started return `424`.
- In-flight calls are cancelled and return `503`. Their handlers may have already run, so treat their outcome as unknown.
- Completed work is **not** rolled back. Use one transactional endpoint when an operation must be all-or-nothing.

## Configuration

All settings are optional. The defaults are shown.

```properties
# Endpoint
composite.base-path=/api/composite
composite.controller.enabled=true
# Lists the routes a batch can reach at {base-path}/endpoints. Off by default.
composite.controller.list-endpoints=false

# Batch limits
composite.max-sub-requests-per-composite=25
composite.max-concurrent-sub-requests=5
composite.max-concurrent-batches=200
composite.max-depth=10
composite.max-request-size=2MB
composite.max-resolved-size=1MB

# Time limits
composite.request-timeout=60s
# Unset by default, so each sub-request uses composite.request-timeout
# composite.sub-request-timeout=10s

# Headers
composite.security.forwarded-headers=Authorization,Cookie
composite.security.client-allowed-headers=Accept,Accept-Language,If-Match,If-None-Match,If-Modified-Since,If-Unmodified-Since
# Empty by default
# composite.security.blocked-headers=X-User-Id
composite.security.forward-client-address=true
composite.header-injection.enabled=true
# Sub-response headers returned to the client. Set-Cookie is never returned.
composite.response-headers=Location,ETag

# Loopback calls
# jdk (no extra dependency) or webclient (requires spring-boot-starter-webflux)
composite.loopback.client=jdk
composite.loopback.protocol=http
composite.loopback.connect-timeout=5s
# Unset by default. Limits the wait for response headers.
# composite.loopback.response-timeout=10s
composite.loopback.max-response-size=256KB
# Unset by default, so it follows the server's limits. See "Loopback calls and threads".
# composite.loopback.max-connections=100
# Unset by default, so HTTPS calls trust the certificate in server.ssl. See "HTTPS loopback".
# composite.loopback.ssl-bundle=loopback

# Debugging. Adds resolved URLs and bodies to responses, so keep it off in production.
composite.debug-enabled=false
```

| Setting | Description |
|---|---|
| `base-path` | Path of the built-in endpoint, which is `{base-path}/execute`. |
| `controller.enabled` | Turn off the built-in endpoint to expose batches from [your own controller](#expose-batches-from-your-own-controller). |
| `controller.list-endpoints` | Turn on `GET {base-path}/endpoints`, which lists every route a batch can reach. Keep it off in production. |
| `max-sub-requests-per-composite` | Larger batches are rejected with `400`. |
| `max-concurrent-sub-requests` | How many sub-requests of one batch run at once. |
| `max-concurrent-batches` | How many batches run at once across the application. A running batch holds no server thread but keeps its results in memory. Batches over the limit get `503` with `Retry-After`. |
| `max-depth` | The longest chain of sub-requests that depend on each other. |
| `max-request-size` | Enforced while the body is read, including chunked requests. Larger batches get `413`. |
| `max-resolved-size` | How much referenced data one sub-request may insert. It stops a small request from multiplying a large response. |
| `request-timeout` | Deadline for the whole batch. |
| `sub-request-timeout` | Deadline for each loopback call. |
| `security.forwarded-headers` | Headers copied from the outer request to every sub-request. |
| `security.client-allowed-headers` | Headers a client may set on a sub-request. |
| `security.blocked-headers` | Headers that are never accepted from clients, even if allowlisted. |
| `security.forward-client-address` | Adds `X-Forwarded-For`, `-Proto`, `-Host` and `-Port` describing the outer request. With `server.forward-headers-strategy` set to `native` or `framework`, sub-requests then see the real client address. |
| `response-headers` | Sub-response headers copied into each sub-response's `headers`. |
| `header-injection.*` | Tracking headers added to each sub-request. See [Tracking headers](#tracking-headers). |
| `loopback.max-response-size` | Larger sub-responses fail with `503`. Each batch can hold one such response per sub-request in memory. |
| `loopback.max-connections` | Loopback calls sent at once, across all batches. |

If `composite.request-timeout` elapses, the response is `504`, in-flight calls are cancelled and unstarted sub-requests don't run. Embedded Tomcat doesn't report a client disconnect while a batch is waiting, so a disconnected batch continues until it finishes or reaches this timeout. A handler that is already running can't be stopped, so use idempotency keys on non-idempotent endpoints.

### Loopback calls and threads

Sub-requests are sent with the non-blocking JDK `HttpClient` by default, so Composite needs no Netty or Reactor Netty. Set `composite.loopback.client=webclient` and add `spring-boot-starter-webflux` to use WebClient instead. Neither client holds a thread while it waits for a response. Refused connections are retried up to three times, because a refused call never reached a handler. Other failures are not retried.

`composite.loopback.max-connections` limits loopback calls across all batches. Above it, calls wait in a queue. The server also limits running sub-request handlers to this value plus 10%, and answers `503` above that, so timed-out handlers can't take over the server. The limit is shared, so one client sending many slow batches can delay other batch users. Add per-client rate limiting in production.

The default depends on the thread model:

- **Platform threads:** half of `server.tomcat.threads.max`, so `100` by default. Each running handler holds a Tomcat thread, and half stays free for normal traffic.
- **Virtual threads** (`spring.threads.virtual.enabled=true`, Java 21): half of `server.tomcat.max-connections`, so `4096` by default. Threads are no longer scarce, so the limit follows connections instead. With 20 ms handlers, this gives about three times the throughput of the platform-thread default. Up to that many slow handlers can run at once, so lower `composite.loopback.max-connections` if that matters for your application.

### HTTPS loopback

Set `composite.loopback.protocol=https` when your application serves HTTPS. Loopback calls then trust exactly the certificate configured in `server.ssl`, and no other. This works the same for self-signed and authority-issued certificates, and needs no truststore changes. Host names aren't checked, because only your server holds the private key for that certificate. So a certificate issued for `api.example.com` still works for calls to `localhost`. If the server's bundle is reloaded, loopback calls follow the new certificate.

If your server's TLS isn't configured through `server.ssl`, name an [SSL bundle](https://docs.spring.io/spring-boot/reference/features/ssl.html) that holds the server's own certificate:

```properties
composite.loopback.ssl-bundle=loopback
spring.ssl.bundle.pem.loopback.truststore.certificate=classpath:server.crt
```

The bundle's trust store, or else its key store, must contain the server certificate itself. A certificate authority alone isn't enough. If neither source is available, loopback calls fall back to the JVM truststore and a warning is logged at startup.

Servers that require client certificates (`server.ssl.client-auth=need`) aren't supported, because loopback calls present no client certificate and every sub-request would fail. A warning is logged at startup. Use `client-auth=want`, or check client certificates in a proxy in front of the application.

## Security

✅ **Works with Spring Security out of the box**

Composite makes real HTTP loopback calls to your endpoints, so **your existing Spring Security configuration applies to every sub-request**.

- **Authentication forwarding:** `Authorization` and `Cookie` are forwarded from the outer request to every sub-request by default. Change the list with `composite.security.forwarded-headers`.
- **Authorization:** each sub-request goes through your security filter chain exactly as a direct HTTP call would.
- **Method security:** `@PreAuthorize`, `@Secured` and other method-level annotations work normally.
- **No privilege escalation:** a batch can only reach endpoints the caller is allowed to call directly.

**Supported authentication types:**
- ✅ Bearer tokens (JWT, OAuth2)
- ✅ Basic Authentication
- ✅ Session cookies (JSESSIONID)
- ✅ API keys and other header-based authentication, once the header is added to `composite.security.forwarded-headers`

**Example:**
```java
@GetMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@CompositeEndpoint
public List<User> getUsers() {
    // Only accessible to admins
}
```

If a non-admin includes this endpoint in a batch, that sub-request fails with `403 Forbidden`, while the other authorized sub-requests succeed.

### Tracking headers

Composite adds these headers to every sub-request:
- `X-Composite-Request: true`
- `X-Composite-Request-Id: <unique-id>`
- `X-Composite-Sub-Request-Id: <reference-id>`

Use them for auditing and logging only. A direct client can send the same header names, so they must not be used for authorization.

### Sub-request safety rules

- Targets must be application-relative paths. Absolute URLs, `//host`, dot segments, fragments and path parameters are rejected.
- A sub-request runs only if the handler Spring selects has `@CompositeEndpoint`. This also applies to handlers from other handler mappings, such as Actuator endpoints on the main port, which are refused with `403`. Because the built-in endpoint isn't annotated, a batch can't call another batch.
- Clients can set only headers in `composite.security.client-allowed-headers`. Credential, proxy, identity and hop-by-hop headers are always refused, and so is any header on `composite.security.forwarded-headers`, so a caller can't swap in another credential for one sub-request.
- Forwarded headers are copied exactly as the caller sent them. Forward only credentials. If your app trusts a header such as `X-Forwarded-For` because a proxy sets it, forwarding it lets callers' values through unchecked.
- Response values are substituted once, so stored data can't act as a `${...}` reference.
- Applications using cookie authentication must keep CSRF protection on the composite endpoint and add their CSRF header to `composite.security.forwarded-headers`.
- The caller stays authenticated for the async batch response when the app uses Spring Security's default security context repositories. A custom `SecurityContextRepository` must support async dispatches.
- A gateway only sees the outer composite URL. Protect annotated endpoints with application-level authorization rather than relying only on gateway path rules.
- Sub-requests connect from the loopback address, and one batch can create up to 25 inner requests by default. Each carries `X-Forwarded-For` with the client's address, so with `server.forward-headers-strategy=native` or `framework` your IP rules and rate limits see the real client. Without that setting they see localhost. Account for the 25-fold fan-out in rate limits either way.
- At `INFO` and above, Composite logs reference IDs, statuses and failure types, never sub-request URLs, headers or response bodies. Rejected requests and error responses are logged at `DEBUG`, where validation messages can include the submitted URL.

## Extending

### Replace how sub-requests are sent

Every default service can be replaced by declaring a bean of its interface. To change how sub-requests are sent, implement `SubRequestDispatcher`:

```java
@Bean
SubRequestDispatcher customDispatcher() {
    return (request, cancellation) -> sendToThisApplication(request, cancellation);
}
```

A dispatcher receives a fully prepared request: references are resolved, and headers are filtered. It must only call the current application and must not follow redirects. Complete the returned stage exceptionally for transport failures. An HTTP error response is a normal result. When the `CancellationSignal` fires, abort the call and release what it holds, because Composite ignores later results.

### Expose batches from your own controller

Set `composite.controller.enabled=false` and accept a `@RequestBody CompositeRequest` in your own controller. Composite answers unreadable bodies on such handlers with its own error format. An `@ExceptionHandler` for `HttpMessageNotReadableException` declared on that controller replaces it. A global `@ControllerAdvice` doesn't, so a global handler for unreadable bodies can't change the composite error format.

## Observability

**Java Flight Recorder.** The library emits `io.github.nabilcarel.composite.Batch` and `io.github.nabilcarel.composite.SubRequest` events. They include timing, status, outcome and the route pattern, but never resolved URLs, headers or bodies.

```bash
java -XX:StartFlightRecording=filename=composite.jfr,settings=profile -jar your-app.jar
```

**Actuator.** With Spring Boot Actuator on the classpath, a `composite` health indicator reports the number of running batches and the available composite endpoints.

**Logging.** Composite logs the registered endpoints at startup, warnings for risky settings, and one `WARN` line per transport failure or timed-out batch. Details are at `DEBUG`.

## How It Works

Composite coordinates HTTP calls to your existing endpoints.

### Request Processing Flow

1. **Acceptance:** Spring MVC authenticates the request to `/api/composite/execute`, reads it and binds it.
2. **Validation:** the batch structure, sizes, headers and targets are checked, and the dependency graph is built from the references. Circular or too-deep dependencies are rejected.
3. **Scheduling:** sub-requests without dependencies start right away, up to `composite.max-concurrent-sub-requests` at once.
4. **Loopback calls:** each sub-request is an HTTP call to your application on `localhost`, or on `server.address` when the server is bound to one address, with forwarded credentials.
5. **Reference resolution:** when a sub-request finishes, the sub-requests waiting on it get their references filled in and start.
6. **Aggregated response:** when every sub-request has a result, or the deadline passes, one response is returned.

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

1. The composite controller receives the accepted request and validates it.
2. First sub-request: the loopback client sends GET `http://localhost:8080/api/account/123`.
    - The `Authorization` header is forwarded from the outer request.
    - The request goes through the full Spring Security filter chain.
    - It reaches `AccountController.getAccount("123")`.
    - The response `{"id": "123", "name": "John Doe"}` is stored as `acc1`.
3. Second sub-request: `${acc1.id}` resolves to `123`.
    - The URL becomes `/api/orders/123`, and it is checked against the annotated routes again.
    - The loopback client sends GET `http://localhost:8080/api/orders/123` with the same credentials.
    - The response is stored as `orders1`.
4. Both results are returned in one response.

### Why This Approach Works

- **Full Spring Security integration:** each sub-request goes through your complete filter chain. Authentication, authorization, method security and custom filters work exactly as they do for direct requests.
- **All Spring features work:** validation, exception handlers, interceptors and other Spring MVC features apply, because each sub-request goes through the normal request lifecycle.
- **No business logic changes:** your controllers stay as they are. The library only adds orchestration and aggregation.
- **Easy debugging and tracing:** breakpoints, stack traces and HTTP tracing tools work on sub-requests like on any other request.

## Frontend Benefits

1. **Fewer HTTP calls, lower latency:** send one composite request instead of many.
2. **Simpler frontend logic:** declare everything a screen needs in one request.
3. **Frontend-driven composition:** combine existing endpoints as needed.
4. **Cleaner state management:** receive all the data in one snapshot.

## Backend Benefits

1. **No more "Backend for Frontend" endpoints:** no custom aggregation endpoints to write and maintain.
2. **Cleaner API design:** encourages small, focused, reusable endpoints.
3. **Separation of concerns:** orchestration is handled by the library.
4. **Independent release cycles:** the frontend can combine endpoints differently without backend changes.
5. **Easy to adopt:** add composite support to an existing endpoint with one `@CompositeEndpoint` annotation.

## FAQ

### Why not just use GraphQL?

GraphQL requires rewriting your API surface with schemas and resolvers. This library works with your existing REST endpoints: you add one annotation. Teams already comfortable with REST have nothing new to learn, and existing controllers need no migration.

### Why not write service layer methods instead?

Service layer aggregations serve one use case each. When the frontend needs a different combination of data tomorrow, you write another method, then another. This library lets the **frontend decide** which combinations it needs, without backend changes.

### What about Salesforce's Composite API?

This follows Salesforce's approach, adapted for Spring Boot. You don't need a Salesforce license or ecosystem. It works with your existing Spring controllers using familiar Spring patterns.

### What happens if one request in the batch fails?

You get partial results, with a status for each sub-request. A failed sub-request doesn't fail the whole batch: the response is `200` with `hasErrors: true`. Sub-requests that depend on the failed one don't run and return `424`. With `stopOnFailure`, the rest of the batch stops too.

Batches are **not** transactional. Each endpoint can use `@Transactional` and roll back its own work, but if sub-request 1 succeeds and sub-request 2 fails, sub-request 1's changes are not rolled back. Use one transactional endpoint when an operation must be all-or-nothing.

The whole batch is rejected, before anything runs, only when it is invalid (`400`) or too large (`413`).

### Can I nest composite requests?

No. The composite endpoint isn't annotated with `@CompositeEndpoint`, so a sub-request can't call it, which prevents recursion.

### Is there a limit on batch size?

Yes. The default maximum is 25 sub-requests per batch, matching Salesforce's limit. Change it with `composite.max-sub-requests-per-composite`.

### How does this handle request and response size limits?

A batch body is limited to 2 MB by `composite.max-request-size`, enforced while it is read. Larger batches get `413`. Each sub-response is limited to 256 KB by `composite.loopback.max-response-size`. A larger one fails with `503`. The data one sub-request can insert through references is limited to 1 MB by `composite.max-resolved-size`.

### Does this work with Spring WebFlux or other Java frameworks?

No. The library is built for Spring Boot applications using Spring Web MVC (`spring-boot-starter-web`) and needs servlet-based request handling. Pure Spring WebFlux (reactive) applications are not supported. The optional WebClient setting only changes the client used for loopback calls.

### Does it work with virtual threads?

Yes. With `spring.threads.virtual.enabled=true` on Java 21, handlers run on virtual threads and the loopback limits scale with Tomcat's connection limit. See [Loopback calls and threads](#loopback-calls-and-threads).

## Development

```bash
./gradlew test                          # unit and integration tests
./gradlew packagedJarTest               # the same suite against the packaged jar
./gradlew test -PloopbackClient=webclient
./gradlew test -PvirtualThreads=true -PtestJavaVersion=21
./gradlew jcstress -PjcstressMode=default   # concurrency stress tests
./gradlew jvmProfile                    # latency, allocation, GC and CPU report
./gradlew javadoc
```

CI runs the tests on Linux and Windows, on Java 17 and 21, with both HTTP clients and with virtual threads. It also runs jcstress, the profile and a packaging check. A longer jcstress run happens nightly.

## License

Apache License 2.0
