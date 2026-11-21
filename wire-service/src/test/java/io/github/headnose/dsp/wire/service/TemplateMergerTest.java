package io.github.headnose.dsp.wire.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateMergerTest {

    private final TemplateMerger merger = new TemplateMerger();

    @Test
    void replacesEveryPlaceholder() {
        String result = merger.merge(
                "<wire><loanId>{{loanId}}</loanId><net>{{netSettlement}}</net></wire>",
                Map.of("loanId", "LN-1001", "netSettlement", "27850.00"));

        assertThat(result).isEqualTo("<wire><loanId>LN-1001</loanId><net>27850.00</net></wire>");
    }

    @Test
    void escapesXmlSpecialCharactersInValues() {
        String result = merger.merge("<name>{{dealerName}}</name>",
                Map.of("dealerName", "Smith & Sons <Auto> \"Best\" 'Cars'"));

        assertThat(result).isEqualTo(
                "<name>Smith &amp; Sons &lt;Auto&gt; &quot;Best&quot; &apos;Cars&apos;</name>");
    }

    @Test
    void throwsWhenAPlaceholderHasNoValue() {
        assertThatThrownBy(() -> merger.merge("<a>{{loanId}}</a><b>{{valueDate}}</b>",
                Map.of("loanId", "LN-1001")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("valueDate");
    }

    @Test
    void insertsValuesLiterallyEvenIfTheyLookLikePlaceholders() {
        String result = merger.merge("<a>{{first}}</a><b>{{second}}</b>",
                Map.of("first", "{{second}}", "second", "B"));

        assertThat(result).isEqualTo("<a>{{second}}</a><b>B</b>");
    }

    @Test
    void handlesDollarSignsAndBackslashesInValues() {
        String result = merger.merge("<memo>{{memo}}</memo>",
                Map.of("memo", "Paid $100 \\ thanks"));

        assertThat(result).isEqualTo("<memo>Paid $100 \\ thanks</memo>");
    }

    @Test
    void returnsTemplateUnchangedWhenItHasNoPlaceholders() {
        assertThat(merger.merge("<wire/>", Map.of())).isEqualTo("<wire/>");
    }
}