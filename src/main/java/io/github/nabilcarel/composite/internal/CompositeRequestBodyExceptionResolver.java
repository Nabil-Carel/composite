package io.github.nabilcarel.composite.internal;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Explains unreadable composite bodies for built-in and custom controllers.
 *
 * <p>Only handlers with a {@code @RequestBody CompositeRequest} parameter are affected, and
 * parser messages are never returned because they can echo request content.
 *
 * <p>A custom controller that declares its own {@code @ExceptionHandler} for the exception
 * keeps it: this resolver steps aside. A {@code @ControllerAdvice} does not take precedence,
 * because many applications handle unreadable bodies globally, for example with problem
 * details, and that would silently change the composite error format.
 */
@Slf4j
public final class CompositeRequestBodyExceptionResolver implements HandlerExceptionResolver {

    private final Map<Class<?>, ExceptionHandlerMethodResolver> controllerHandlers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public CompositeRequestBodyExceptionResolver(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    @Nullable
    public ModelAndView resolveException(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                         @Nullable Object handler, @NonNull Exception exception) {
        if (!(exception instanceof HttpMessageNotReadableException)
                || !readsCompositeRequest(handler)
                || handledByController((HandlerMethod) handler, exception)) {
            return null;
        }

        boolean tooLarge = hasCause(exception, CompositeRequestSizeAdvice.RequestTooLargeException.class);
        String message = tooLarge
                ? "Composite request is too large. Split the work into smaller batches."
                : describe(exception);

        try {
            response.setStatus((tooLarge ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST).value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(),
                    CompositeResponse.builder().hasErrors(true).errors(List.of(message)).build());
            return new ModelAndView();
        } catch (IOException e) {
            log.debug("Could not write composite error response", e);
            return null;
        }
    }

    private static boolean readsCompositeRequest(Object handler) {
        return handler instanceof HandlerMethod method && Arrays.stream(method.getMethodParameters())
                .anyMatch(parameter -> parameter.getParameterType() == CompositeRequest.class
                        && parameter.hasParameterAnnotation(RequestBody.class));
    }

    /** Whether the controller class itself declares an {@code @ExceptionHandler} for the exception. */
    private boolean handledByController(HandlerMethod handler, Exception exception) {
        return controllerHandlers
                .computeIfAbsent(handler.getBeanType(), ExceptionHandlerMethodResolver::new)
                .resolveMethodByThrowable(exception) != null;
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable cause = failure; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable exception) {
        JsonLocation location = null;

        for (Throwable cause = exception; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException && String.valueOf(cause.getMessage()).startsWith("Unknown ")) {
                return cause.getMessage();
            }
            if (cause instanceof JsonProcessingException json && json.getLocation() != null) {
                location = json.getLocation();
            }
        }

        String position = location == null ? ""
                : " near line " + location.getLineNr() + ", column " + location.getColumnNr();
        return "Request body is not valid composite JSON" + position
                + ". Expected an object like {\"subRequests\":[{\"referenceId\":\"a\",\"method\":\"GET\",\"url\":\"/api/users/1\"}]}.";
    }
}
