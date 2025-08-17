# Composite Endpoint Library

Batch multiple dependent API calls into a single request — with zero boilerplate.

## Overview

The Composite Endpoint Library for Spring Boot lets you combine multiple REST endpoints into one request, similar to Salesforce’s Composite API. It automatically discovers annotated endpoints, executes them in the correct dependency order, and returns a single aggregated response — eliminating the need to manually chain calls in your frontend or create custom aggregation endpoints in your backend.

## Project Status 🚧

This library is under active development.  
The goal is to fully replicate the behavior of Salesforce’s Composite API — including dependent request execution, automatic endpoint discovery, and batch orchestration.

**Currently working:**
- `@CompositeEndpoint` annotation
- Automatic endpoint registration
- Basic composite request parsing and execution
- Sequential execution for dependent calls
- Full dependency resolution syntax (Salesforce-style reference IDs)

**Planned / In Progress:**
- Parallel execution of independent requests
- More robust error handling & partial failure reporting
- Advanced request/response transformations
- Integration with application security — composite requests will execute using the caller’s authentication context, respecting per-endpoint authorization rules.

## Features

- `@CompositeEndpoint` annotation to opt-in existing endpoints.
- Automatic endpoint registration — no need to repeat paths or change controllers.
- Dependency support — later requests can reference IDs or values from earlier responses.
- Execution ordering based on declared dependencies.
- Works with existing Spring Boot controllers — no extra wiring.
- Aggregated error handling and response structure.

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
- Multi-step workflows that need custom rollback logic
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

⚠️ **Planned Feature — Not Yet Implemented**

Security integration is a core design goal for this library, but it is not available in the current release. Composite requests today do not automatically enforce your Spring Security rules per subrequest — you should only use this in non-sensitive contexts until security features land.

When complete, the Composite Endpoint Library will work within your existing Spring Security configuration:

- **Authentication Context** — All subrequests in a composite call will execute under the same `SecurityContext` as the incoming HTTP request.
- **Authorization Checks** — Standard method-level and URL-based security rules (e.g., `@PreAuthorize`, `@Secured`, or WebSecurity config) will be applied to each subrequest.
- **No Privilege Escalation** — A composite request will not be able to access endpoints the calling user wouldn’t normally be able to reach individually.

**Planned Enhancements:**

- Fine-grained per-subrequest access control logging for auditing.
- Ability to fail fast when any subrequest is unauthorized, or to return partial results depending on configuration.
- Optional request-level security policies — e.g., allow only certain endpoints to be part of composite calls.

## Tech Stack

- Java 17+
- Spring Boot
- Gradle
- JUnit 5, Mockito (for testing)


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
    @CompositeEndpoint(value=Account.class)
    public Account getAccount(@PathVariable String id) {
        // Your business logic
    }
}
```
The `value` parameter specifies the return type for proper JSON deserialization.

### 2. Send a composite request with dependencies

```http
POST /api/composite/execute
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
  "results": [
    {
      "referenceId": "acc1",
      "status": 200,
      "body": { "id": "1", "name": "John Doe" }
    },
    {
      "status": 200,
      "body": [ { "orderId": "A123" }, { "orderId": "B456" } ]
    }
  ]
}
```

## How It Works

The Composite Endpoint Library acts as an intelligent request router that leverages Spring's existing infrastructure rather than creating a custom orchestration engine.

### Request Processing Flow

1. **Request Interception**: A filter intercepts incoming requests to `/api/composite/execute`
2. **Dependency Analysis**: The library parses subrequests and resolves dependencies between them
3. **Sequential Execution**: Endpoints annotated with `@CompositeEndpoint` are forwarded to their actual Spring controllers using `dispatcher.forward()`
4. **Response Capture**: An interceptor captures each controller's response using a custom response wrapper
5. **Dependency Resolution**: Results are stored in a shared map and referenced by subsequent requests using Spring's property resolution
6. **Aggregated Response**: All individual results are combined into a single JSON response

### Why This Approach Works

**Debugging is straightforward**: Since each subrequest hits your actual controller methods, you can set breakpoints, view stack traces, and use all your normal debugging tools exactly as you would for direct API calls.

**All Spring features work**: Security filters, validation annotations, exception handlers, and request interceptors all function normally because each subrequest goes through Spring's complete request lifecycle.

**No business logic changes**: Your existing controllers remain unchanged. The library only adds routing and response aggregation on top.

**Dependency resolution is simple**: When a request references `${acc1.id}`, the library looks up `acc1` in the results map and uses Spring's `BeanWrapperImpl` to resolve the `id` property - the same property resolution used throughout the Spring ecosystem.

### Request Flow Example

```
POST /api/composite/execute
{
  "subRequests": [
    { "method": "GET", "url": "/api/account/123", "referenceId": "acc1" },
    { "method": "GET", "url": "/api/orders/${acc1.id}", "referenceId": "orders1" }
  ]
}
```

**What happens internally:**

1. Filter intercepts the composite request
2. First subrequest: `dispatcher.forward()` to `/api/account/123`
    - Hits `AccountController.getAccount(123)`
    - Response captured: `{"id": "123", "name": "John Doe"}`
    - Stored in results map as `acc1`
3. Second subrequest: `${acc1.id}` resolved to `123` using `BeanWrapperImpl`
    - URL becomes `/api/orders/123`
    - `dispatcher.forward()` to `/api/orders/123`
    - Hits `OrderController.getOrders(123)`
    - Response captured and stored as `orders1`
4. Both results returned in aggregated response

### Benefits of This Design

- **Zero custom orchestration**: No need to manually invoke Spring beans or manage transactions
- **Full Spring compatibility**: Every Spring feature works exactly as expected
- **Easy testing**: Each endpoint can be unit tested independently
- **Simple debugging**: Set breakpoints in your actual controller methods
- **Familiar patterns**: Uses standard Spring property resolution syntax (`${object.property[0].field}`)
- **Security integration**: When implemented, each subrequest will go through the full Spring Security filter chain

## Example Endpoints

The library can automatically register endpoints like:

- `GET /api/composite/test`
- `POST /api/composite/testPost`
- `GET /api/composite/helloWorld`
- `GET /api/composite/data`
- `GET /api/composite/data/{id}`

## Frontend Benefits

1. **Fewer HTTP Calls, Lower Latency:** Send one composite request instead of many.
2. **Simplified Frontend Logic:** Declare all needs in one request.
3. **Improved UI Responsiveness:** Parallel execution supports progressive rendering.
4. **Frontend-Driven Composition:** Compose existing endpoints as needed.
5. **Cleaner State Management:** Receive all required data in one snapshot.

## Backend Benefits

1. **No More “Backend for Frontend” Hell:** No need for custom aggregation endpoints.
2. **Promotes Clean API Design:** Encourages small, focused, reusable endpoints.
3. **Separation of Concerns:** Orchestration is handled by the library.
4. **No Coordination Debt:** Decouple frontend and backend release cycles.
5. **Code Reuse and Performance Gains:** Identical calls can be reused within a composite request.
6. **Scalable and Maintainable:** Add composite support with a single annotation.

## Project Structure

- `src/main/java/com/example/composite/` — Main application code
- `src/test/java/com/example/composite/` — Unit tests

## License

MIT License
