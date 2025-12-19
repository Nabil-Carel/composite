package io.github.nabilcarel.composite.controller;

import io.github.nabilcarel.composite.annotation.CompositeEndpoint;
import io.github.nabilcarel.composite.config.EndpointRegistry;
import io.github.nabilcarel.composite.model.ResponseTracker;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

@RestController
@Lazy
@RequestMapping("${composite.base-path:/api/composite}")
@ConditionalOnProperty(name = "composite.controller.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CompositeController {
    private final ConcurrentMap<String, ResponseTracker> responseStore;
    @Lazy
    private final CompositeRequestService requestService;

   @lombok.Data
    static class Data {
        private String name;
        private String value;
        private int id;
    }

    @PostMapping("/execute")
    public CompletableFuture<ResponseEntity<CompositeResponse>> execute(HttpServletRequest request,
                                                                        HttpServletResponse response) {
       return requestService.execute(request, response);
    }

    @GetMapping("/endpoints")
    public Set<EndpointRegistry.EndpointInfo> getAvailableEndpoints() {
        return requestService.getAvailableEndpoints();
    }

    @GetMapping("/test")
    @CompositeEndpoint(value = SubResponse.class)
    public ResponseEntity<SubResponse> getTest() {
        return ResponseEntity.ok()
                .header("bearer", "aaaa")
                .body(SubResponse.builder().body("test").referenceId("test").build());
    }

    @PostMapping("/testPost")
    @CompositeEndpoint(value = SubResponse.class)
    public ResponseEntity<SubResponse> getTestPost(@RequestBody Data data) {
        SubResponse subResponse = SubResponse.builder().body(String.valueOf(data.id)).referenceId("testPost").build();
        return ResponseEntity.ok(subResponse);
    }

    @GetMapping("/helloWorld")
    @CompositeEndpoint(value = String.class)
    public ResponseEntity<String> getHello() {
        return ResponseEntity.ok("helloWorld");
    }

    @GetMapping("/num")
    @CompositeEndpoint(value = Integer.class)
    public ResponseEntity<Integer> getNum() {
        return ResponseEntity.ok(0);
    }

    @GetMapping("/data")
    @CompositeEndpoint(value = Data.class)
    public ResponseEntity<Data> getData() {
        Data data = new Data();
        data.setName("testName");
        data.setValue("testValue");
        data.setId(1);
        return ResponseEntity.ok(data);
    }

    @CompositeEndpoint(value= Data.class)
    @GetMapping("/data/{id}")
    public ResponseEntity<Data> getData2(@PathVariable int id) {
        Data data = new Data();
        data.setName("testName2");
        data.setValue("testValue2");
        data.setId(id);
        return ResponseEntity.ok(data);
    }


}
