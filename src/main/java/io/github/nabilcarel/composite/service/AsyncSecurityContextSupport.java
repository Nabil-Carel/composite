package io.github.nabilcarel.composite.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Keeps the caller authenticated when Spring MVC re-dispatches an async composite response.
 *
 * <p>A hand-written {@code OncePerRequestFilter} usually authenticates only the initial
 * dispatch. Without this support, Spring Security would reject the async dispatch after the
 * batch had already run. The context is stored only on the current request.
 *
 * <p>This class references Spring Security and must only be loaded when it is present.
 *
 * @since 0.0.1
 */
final class AsyncSecurityContextSupport {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();
    private static final RequestAttributeSecurityContextRepository REPOSITORY =
            new RequestAttributeSecurityContextRepository();

    private AsyncSecurityContextSupport() {
    }

    static void preserve(HttpServletRequest request, HttpServletResponse response) {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context.getAuthentication();

        if (authentication != null && !TRUST_RESOLVER.isAnonymous(authentication)) {
            REPOSITORY.saveContext(context, request, response);
        }
    }
}
