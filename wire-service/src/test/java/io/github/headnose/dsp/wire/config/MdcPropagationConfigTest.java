package io.github.headnose.dsp.wire.config;

import io.github.headnose.dsp.wire.logging.MdcKeys;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcPropagationConfigTest {

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void submissionIdReachesAnotherThreadAndIsRemovedAfterTheTask() throws Exception {
        MdcPropagationConfig.register(ContextRegistry.getInstance());
        MDC.put(MdcKeys.SUBMISSION_ID, "SUB-1");
        AtomicReference<String> inTask = new AtomicReference<>();
        AtomicReference<String> afterTask = new AtomicReference<>("not checked");

        // decorate() takes the snapshot now, on the "request" thread
        Runnable task = new ContextPropagatingTaskDecorator()
                .decorate(() -> inTask.set(MDC.get(MdcKeys.SUBMISSION_ID)));
        Thread worker = new Thread(() -> {
            task.run();
            afterTask.set(MDC.get(MdcKeys.SUBMISSION_ID));   // a reused thread must not keep the value
        });
        worker.start();
        worker.join();

        assertThat(inTask.get()).isEqualTo("SUB-1");
        assertThat(afterTask.get()).isNull();
    }
}