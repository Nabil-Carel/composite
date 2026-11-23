package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.nabilcarel.composite.util.SubRequestTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SubRequestTargetTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "api/users",
        "http://evil.example/api",
        "https://evil.example/api",
        "//evil.example/api",
        "/\\evil.example/api",
        "/api//users",
        "/api/./users",
        "/api/../users",
        "/api/users/..",
        "/api/%2e%2e/users",
        "/api/users%2F1",
        "/api/users%5C1",
        "/api/users%3Bx",
        "/api/users%3Fx",
        "/api/users%23x",
        "/api/users%00",
        "/api/users;x=1",
        "/api/users#fragment",
        "/api/users?a=1#fragment",
        "/api/%zz",
        "/api/users?q=%zz",
        "/api/${unterminated",
        "/api/${${a.b}.c}",
        "/api/line\nbreak"
    })
    void parse_rejectsUnsafeTargets(String url) {
        assertThatThrownBy(() -> SubRequestTarget.parse(url)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "/api/users, /api/users",
        "/api/users/, /api/users/",
        "/api/users?page=2&sort=name, /api/users?page=2&sort=name",
        "/api/a%20b, /api/a%20b",
        "/api/a b, /api/a%20b",
        "/api/search?q=a%20b, /api/search?q=a%20b"
    })
    void render_keepsLiteralUrlsCorrectlyEncoded(String url, String expected) {
        assertThat(SubRequestTarget.parse(url).render(expression -> "unused")).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "1?role=admin, 1%3Frole%3Dadmin",
        "me;x=1, me%3Bx%3D1",
        "a b, a%20b",
        "%2F, %252F",
        "${secret.token}, %24%7Bsecret.token%7D"
    })
    void render_encodesValuesInsideOnePathSegment(String value, String expected) {
        String url = SubRequestTarget.parse("/api/users/${a.id}/details").render(expression -> value);

        assertThat(url).isEqualTo("/api/users/" + expected + "/details");
    }

    @Test
    void render_encodesQueryValuesSoTheyCannotAddParameters() {
        String url = SubRequestTarget.parse("/api/search?q=${a.q}&page=1").render(expression -> "x&admin=true");

        assertThat(url).isEqualTo("/api/search?q=x%26admin%3Dtrue&page=1");
    }

    @Test
    void render_supportsSeveralReferencesInOneSegment() {
        Map<String, String> values = Map.of("a.x", "left", "b.y", "right");

        String url = SubRequestTarget.parse("/api/${a.x}-${b.y}").render(values::get);

        assertThat(url).isEqualTo("/api/left-right");
    }

    @Test
    void parse_doesNotTreatCharactersInsideReferencesAsSeparators() {
        String url = SubRequestTarget.parse("/api/${a['x/y?z']}").render(expression -> {
            assertThat(expression).isEqualTo("a['x/y?z']");
            return "value";
        });

        assertThat(url).isEqualTo("/api/value");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "a/b", "a\\b", "", "a\nb"})
    void render_rejectsValuesThatChangePathStructure(String value) {
        SubRequestTarget target = SubRequestTarget.parse("/api/${a.id}/details");

        assertThatThrownBy(() -> target.render(expression -> value))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void render_rejectsValueCompletingADotSegment() {
        SubRequestTarget target = SubRequestTarget.parse("/api/.${a.id}/details");

        assertThatThrownBy(() -> target.render(expression -> "."))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
        "/api/users/1?page=2, /api/users/1",
        "/api/users/${a.id}, /api/users/composite-reference",
        "/api/users/1%3Frole%3Dadmin, /api/users/1%3Frole%3Dadmin"
    })
    void routablePath_returnsPathWithoutQuery(String url, String expected) {
        assertThat(SubRequestTarget.routablePath(url)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "http://evil.example/api", "//evil.example/api", "/api/../admin", "/api/%2e%2e/admin",
        "/api/a%2Fb", "/api/users;x=1", "/api/users#x", "relative"
    })
    void routablePath_rejectsUnsafeUrls(String url) {
        assertThat(SubRequestTarget.routablePath(url)).isEmpty();
    }
}
