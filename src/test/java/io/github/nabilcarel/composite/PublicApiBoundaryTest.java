package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nabilcarel.composite.dispatch.CancellationSignal;
import io.github.nabilcarel.composite.dispatch.DispatchResult;
import io.github.nabilcarel.composite.dispatch.PreparedSubRequest;
import io.github.nabilcarel.composite.dispatch.SubRequestDispatcher;
import io.github.nabilcarel.composite.internal.DefaultCancellationSignal;
import io.github.nabilcarel.composite.model.request.CompositeRequest;
import io.github.nabilcarel.composite.model.request.SubRequestDto;
import io.github.nabilcarel.composite.model.response.CompositeResponse;
import io.github.nabilcarel.composite.model.response.SubResponse;
import io.github.nabilcarel.composite.service.AuthenticationForwardingService;
import io.github.nabilcarel.composite.service.CompositeRequestService;
import io.github.nabilcarel.composite.service.CompositeRequestValidator;
import io.github.nabilcarel.composite.service.ReferenceResolverService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicApiBoundaryTest {

    @ParameterizedTest
    @ValueSource(classes = {
        CompositeRequestService.class, CompositeRequestValidator.class, ReferenceResolverService.class,
        AuthenticationForwardingService.class, SubRequestDispatcher.class, CancellationSignal.class,
        PreparedSubRequest.class, DispatchResult.class, CompositeRequest.class, SubRequestDto.class,
        CompositeResponse.class, SubResponse.class
    })
    void publicApi_doesNotExposeReactorOrWebClient(Class<?> type) {
        List<String> signatures = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                signatures.add(method.getGenericReturnType().getTypeName());
                for (Type parameter : method.getGenericParameterTypes()) {
                    signatures.add(parameter.getTypeName());
                }
            }
        }

        assertThat(signatures).noneMatch(signature -> signature.contains("reactor.")
            || signature.contains("io.netty.") || signature.contains("web.reactive."));
    }

    @Test
    void cancellationSignal_runsEachActionOnce_evenWhenRegisteredLate() {
        DefaultCancellationSignal signal = new DefaultCancellationSignal();
        AtomicInteger early = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();

        signal.onCancel(early::incrementAndGet);
        signal.cancel();
        signal.cancel();
        signal.onCancel(late::incrementAndGet);

        assertThat(signal.isCancelled()).isTrue();
        assertThat(early).hasValue(1);
        assertThat(late).hasValue(1);
    }
}
