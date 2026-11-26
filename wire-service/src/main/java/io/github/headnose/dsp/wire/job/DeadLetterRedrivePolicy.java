package io.github.headnose.dsp.wire.job;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Decides what the redrive job does with one dead-lettered record (blueprint "Support / Retry Job").
 * No Kafka calls, no clock of its own: the job passes in "now", so the rules are unit-tested directly.
 * <ul>
 *   <li>PARK: not retryable (bad JSON, invalid event, ...), no exception header, or already redriven
 *       maxRedrives times. The record stays in the DLQ (30 days) for a person to look at.</li>
 *   <li>WAIT: not due yet. Due = the record's timestamp + delay.</li>
 *   <li>REDRIVE: send it back to its original topic.</li>
 * </ul>
 */
public final class DeadLetterRedrivePolicy {

    /** Our own header: how many times this event was already sent back from the DLQ. Kept across DLQ trips. */
    public static final String REDRIVE_COUNT_HEADER = "dsp-redrive-count";

    /** Longest single wait: the job re-checks at least this often (the consumer pauses while it waits). */
    static final Duration MAX_WAIT = Duration.ofSeconds(30);

    public enum Action { PARK, WAIT, REDRIVE }

    public record Decision(Action action, Duration pause, String reason) {
        static Decision park(String reason) { return new Decision(Action.PARK, Duration.ZERO, reason); }
        static Decision waitFor(Duration pause) { return new Decision(Action.WAIT, pause, "not due yet"); }
        static Decision redrive() { return new Decision(Action.REDRIVE, Duration.ZERO, "due"); }
    }

    private final Duration delay;
    private final int maxRedrives;
    private final List<Class<? extends Throwable>> notRetryable;

    public DeadLetterRedrivePolicy(Duration delay, int maxRedrives,
                                   List<? extends Class<? extends Throwable>> notRetryable) {
        this.delay = delay;
        this.maxRedrives = maxRedrives;
        this.notRetryable = List.copyOf(notRetryable);
    }

    public Decision decide(ConsumerRecord<?, ?> record, Instant now) {
        // Set by Spring's DeadLetterPublishingRecoverer: the class of our exception (the listener's
        // ListenerExecutionFailedException wraps it, so the CAUSE is the one that matters).
        String cause = headerValue(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
        if (cause == null) {
            cause = headerValue(record, KafkaHeaders.DLT_EXCEPTION_FQCN);
        }
        if (cause == null) {
            return Decision.park("no exception header: not dead-lettered by this service");
        }
        if (isNotRetryable(cause)) {
            return Decision.park("not retryable: " + cause);
        }
        int redrives = redriveCount(record);
        if (redrives >= maxRedrives) {
            return Decision.park("gave up after " + redrives + " redrives, last cause " + cause);
        }
        Instant due = Instant.ofEpochMilli(record.timestamp()).plus(delay);
        if (now.isBefore(due)) {
            Duration remaining = Duration.between(now, due);
            return Decision.waitFor(remaining.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : remaining);
        }
        return Decision.redrive();
    }

    public static int redriveCount(ConsumerRecord<?, ?> record) {
        String value = headerValue(record, REDRIVE_COUNT_HEADER);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A class we cannot load (renamed, from another version) counts as retryable: maxRedrives still bounds it. */
    private boolean isNotRetryable(String className) {
        Class<?> type;
        try {
            type = Class.forName(className, false, DeadLetterRedrivePolicy.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
        return notRetryable.stream().anyMatch(nr -> nr.isAssignableFrom(type));
    }

    /** Last header with that name (Spring may add DLT headers again on a second trip). */
    static String headerValue(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
