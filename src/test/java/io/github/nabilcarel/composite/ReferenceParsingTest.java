package io.github.nabilcarel.composite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.nabilcarel.composite.exception.ReferenceResolutionException;
import io.github.nabilcarel.composite.util.ReferencePath;
import io.github.nabilcarel.composite.util.ReferenceTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void template_splitsLiteralsAndReferences() {
        ReferenceTemplate template = ReferenceTemplate.parse("Hello ${a.name}, #${b.id}!");

        assertThat(template.hasReferences()).isTrue();
        assertThat(template.isSingleReference()).isFalse();
        assertThat(template.render(expression -> "[" + expression + "]")).isEqualTo("Hello [a.name], #[b.id]!");
    }

    @Test
    void template_recognizesAWholeValueReference() {
        ReferenceTemplate template = ReferenceTemplate.parse("${a.id}");

        assertThat(template.isSingleReference()).isTrue();
        assertThat(template.tokens().get(0).text()).isEqualTo("a.id");
    }

    @Test
    void template_neverRescansInsertedValues() {
        String rendered = ReferenceTemplate.parse("${a.bio}").render(expression -> {
            assertThat(expression).isEqualTo("a.bio");
            return "${secret.token}";
        });

        assertThat(rendered).isEqualTo("${secret.token}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "price ${", "${}", "$100", "{a}"})
    void template_keepsNonReferencesLiteral(String text) {
        ReferenceTemplate template = ReferenceTemplate.parse(text);

        assertThat(template.hasReferences()).isFalse();
        assertThat(template.render(expression -> "unused")).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"${${a.b}.c}", "${a${b}}", "x ${a{b}"})
    void template_rejectsNestedReferences(String text) {
        assertThatThrownBy(() -> ReferenceTemplate.parse(text))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nested");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "a.user.name | Ada",
        "a.items[0].name | first",
        "a['db.host'] | localhost",
        "a[\"db.host\"] | localhost",
        "a[user][name] | Ada",
        "a.user['name'] | Ada",
        "a.numeric[7] | seven"
    })
    void path_navigatesJson(String expression, String expected) throws Exception {
        JsonNode root = mapper.readTree("""
            {"user":{"name":"Ada"},"items":[{"name":"first"}],"db.host":"localhost","numeric":{"7":"seven"}}
            """);

        ReferencePath path = ReferencePath.parse(expression);

        assertThat(path.referenceId()).isEqualTo("a");
        assertThat(path.navigate(root).asText()).isEqualTo(expected);
    }

    @Test
    void path_withoutSteps_returnsTheRoot() throws Exception {
        JsonNode root = mapper.readTree("{\"id\":1}");

        assertThat(ReferencePath.parse("a").navigate(root)).isSameAs(root);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", ".a", "[0]", "a.", "a..b", "a[", "a[]", "a['x", "a['x]", "a[0]b", "a[99999999999]"
    })
    void path_rejectsMalformedExpressions(String expression) {
        assertThatThrownBy(() -> ReferencePath.parse(expression)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.missing", "a.items[1]", "a.items.name", "a.user.name.more", "a.user[0]"})
    void path_reportsMissingValues(String expression) throws Exception {
        JsonNode root = mapper.readTree("{\"user\":{\"name\":\"Ada\"},\"items\":[{\"name\":\"first\"}]}");
        ReferencePath path = ReferencePath.parse(expression);

        assertThatThrownBy(() -> path.navigate(root)).isInstanceOf(ReferenceResolutionException.class);
    }
}
