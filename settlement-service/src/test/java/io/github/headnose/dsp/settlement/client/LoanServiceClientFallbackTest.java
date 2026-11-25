package io.github.headnose.dsp.settlement.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoanServiceClientFallbackTest {

    /**
     * Guards the fix for a race in Resilience4j's FallbackMethod: non-public fallbacks get their
     * accessible flag toggled per call on a shared Method, and concurrent fallbacks can then fail with
     * IllegalAccessException (seen in the import load test). The race itself is too rare to reproduce
     * reliably in a unit test, so this checks the condition that rules it out.
     */
    @Test
    void allFallbackOverloadsArePublic() {
        List<Method> fallbacks = Arrays.stream(LoanServiceClient.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(LoanServiceClient.FALLBACK_METHOD))
                .toList();

        assertThat(fallbacks).hasSize(3);
        assertThat(fallbacks).allSatisfy(m ->
                assertThat(Modifier.isPublic(m.getModifiers()))
                        .as("%s must be public", m)
                        .isTrue());
    }
}