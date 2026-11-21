package io.github.headnose.dsp.wire.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills a wire template's {{name}} placeholders with values (decision D8).
 * Pure: no I/O, no state.
 */
@Component
public class TemplateMerger {

    /** {{name}}: two literal braces, a word (letters, digits, _) captured as group 1, two braces. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    /**
     * @param template text containing {{name}} placeholders
     * @param values   placeholder name -> raw (unescaped) value
     * @return the template with every placeholder replaced by its XML-escaped value
     * @throws IllegalStateException if a placeholder has no value (the message names it)
     */
    public String merge(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String name = matcher.group(1);
            String value = values.get(name);
            if (value == null) {
                throw new IllegalStateException("No value for template placeholder {{" + name + "}}");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(escapeXml(value)));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /** Escapes the five XML special characters. '&' must go first, or it would re-escape the others. */
    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}