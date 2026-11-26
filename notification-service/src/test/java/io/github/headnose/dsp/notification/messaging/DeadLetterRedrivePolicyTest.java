package io.github.headnose.dsp.notification.messaging;

import io.github.headnose.dsp.notification.service.InvalidEmailDeliveryEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.github.headnose.dsp.notification.messaging.DeadLetterRedrivePolicy.Action.PARK;
import static io.github.headnose.dsp.notification.messaging.DeadLetterRedrivePolicy.Action.REDRIVE;
import static io.github.headnose.dsp.notification.messaging.DeadLetterRedrivePolicy.Action.WAIT;
import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterRedrivePolicyTest {

    private static final Instant DEAD_LETTERED = Instant.parse("2026-11-26T12:00:00Z");
    private static final String RETRYABLE = IllegalStateException.class.getName();

    private final DeadLetterRedrivePolicy policy = new DeadLetterRedrivePolicy(
            Duration.ofMinutes(5), 3, List.of(InvalidEmailDeliveryEventException.class, JacksonException.class));

    @Test
    void retryableFailureWaitsUntilTheDelayHasPassed() {
        DeadLetterRedrivePolicy.Decision decision = policy.decide(record(RETRYABLE, null), DEAD_LETTERED.plusSeconds(10));

        assertThat(decision.action()).isEqualTo(WAIT);
        assertThat(decision.pause()).isEqualTo(DeadLetterRedrivePolicy.MAX_WAIT);   // capped: re-checks every 30 s
    }

    @Test
    void lastWaitIsOnlyTheTimeThatIsLeft() {
        DeadLetterRedrivePolicy.Decision decision =
                policy.decide(record(RETRYABLE, null), DEAD_LETTERED.plus(Duration.ofMinutes(5)).minusSeconds(4));

        assertThat(decision.action()).isEqualTo(WAIT);
        assertThat(decision.pause()).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void retryableFailureIsRedrivenOnceDue() {
        assertThat(policy.decide(record(RETRYABLE, null), DEAD_LETTERED.plus(Duration.ofMinutes(5))).action())
                .isEqualTo(REDRIVE);
    }

    @Test
    void notRetryableFailureIsParked() {
        DeadLetterRedrivePolicy.Decision decision =
                policy.decide(record(InvalidEmailDeliveryEventException.class.getName(), null), DEAD_LETTERED.plus(Duration.ofHours(1)));

        assertThat(decision.action()).isEqualTo(PARK);
        assertThat(decision.reason()).startsWith("not retryable");
    }

    @Test
    void subclassOfANotRetryableTypeIsParkedToo() {
        assertThat(policy.decide(record(StreamReadException.class.getName(), null), DEAD_LETTERED.plus(Duration.ofHours(1)))
                .action()).isEqualTo(PARK);
    }

    @Test
    void parkedAfterMaxRedrives() {
        DeadLetterRedrivePolicy.Decision decision =
                policy.decide(record(RETRYABLE, "3"), DEAD_LETTERED.plus(Duration.ofHours(1)));

        assertThat(decision.action()).isEqualTo(PARK);
        assertThat(decision.reason()).startsWith("gave up after 3 redrives");
    }

    @Test
    void redrivenAgainWhileBelowMaxRedrives() {
        assertThat(policy.decide(record(RETRYABLE, "2"), DEAD_LETTERED.plus(Duration.ofHours(1))).action())
                .isEqualTo(REDRIVE);
    }

    @Test
    void recordWithoutExceptionHeaderIsParked() {
        ConsumerRecord<String, String> noHeaders = new ConsumerRecord<>("dlq", 0, 0L, DEAD_LETTERED.toEpochMilli(),
                TimestampType.CREATE_TIME, 0, 0, "SUB-1", "{}", new RecordHeaders(), Optional.empty());

        assertThat(policy.decide(noHeaders, DEAD_LETTERED.plus(Duration.ofHours(1))).action()).isEqualTo(PARK);
    }

    @Test
    void unknownExceptionClassCountsAsRetryable() {
        assertThat(policy.decide(record("com.example.GoneException", null), DEAD_LETTERED.plus(Duration.ofHours(1)))
                .action()).isEqualTo(REDRIVE);
    }

    private static ConsumerRecord<String, String> record(String causeClass, String redriveCount) {
        RecordHeaders headers = new RecordHeaders();
        headers.add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                "org.springframework.kafka.listener.ListenerExecutionFailedException".getBytes(StandardCharsets.UTF_8));
        headers.add(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, causeClass.getBytes(StandardCharsets.UTF_8));
        if (redriveCount != null) {
            headers.add(DeadLetterRedrivePolicy.REDRIVE_COUNT_HEADER, redriveCount.getBytes(StandardCharsets.UTF_8));
        }
        return new ConsumerRecord<>("dlq", 0, 0L, DEAD_LETTERED.toEpochMilli(), TimestampType.CREATE_TIME,
                0, 0, "SUB-1", "{}", headers, Optional.empty());
    }
}
