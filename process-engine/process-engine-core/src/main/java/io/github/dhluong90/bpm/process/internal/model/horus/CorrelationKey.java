package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:correlationKey}: the name of the instance variable whose value correlates a message to this step. On
 * a catch event or receive task the value is read when the token arrives and the subscription keeps it; on a send
 * task or throw event the value travels with the message.
 *
 * @param variable the variable name
 */
public record CorrelationKey(
        String variable) implements HorusExtension {
}
