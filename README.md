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
      "url": "/api/orders/{acc1.id}"
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

- A request filter intercepts `/api/composite/execute` calls.
- The library parses subrequests and resolves dependencies between them.
- Endpoints annotated with `@CompositeEndpoint` are invoked directly on their Spring beans.
- The engine executes calls in the correct order based on dependencies.
- All results are returned as one aggregated JSON response.

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
