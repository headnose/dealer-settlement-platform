package io.github.headnose.dsp.notification.config;

import io.github.headnose.dsp.notification.messaging.EmailDeliveryRecoverer;
import io.github.headnose.dsp.notification.service.InvalidEmailDeliveryEventException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

/** Retry-then-recover for the email-delivery listener (B5). Boot plugs this handler into the listener container. */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    DefaultErrorHandler emailDeliveryErrorHandler(EmailDeliveryRecoverer recoverer) {
        // 2 retries, 1 s apart (3 attempts total), then the recoverer
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));

        // Retrying can't fix these: straight to the recoverer (-> DLQ)
        handler.addNotRetryableExceptions(InvalidEmailDeliveryEventException.class, JacksonException.class);
        return handler;
    }
}