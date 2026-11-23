package io.github.nabilcarel.composite.integration.controllers;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/** Host-application components that composite execution must coexist with. */
@Configuration
public class TestLifecycleConfiguration {

    public static final String FILTER_HEADER = "X-Host-Filter";

    @Bean
    Filter hostHeaderFilter() {
        return (request, response, chain) -> {
            ((HttpServletResponse) response).setHeader(FILTER_HEADER, "present");
            chain.doFilter(request, response);
        };
    }

    /** A functional route that shadows the annotated {@code /api/sec/users/{id}} mapping. */
    @Bean
    RouterFunction<ServerResponse> hiddenFunctionalRoute() {
        return RouterFunctions.route()
                .GET("/api/sec/users/functional", request -> {
                    TestSecurityController.HIDDEN_CALLS.incrementAndGet();
                    return ServerResponse.ok().body(Map.of("handler", "NOT_ALLOWLISTED"));
                })
                .build();
    }
}
