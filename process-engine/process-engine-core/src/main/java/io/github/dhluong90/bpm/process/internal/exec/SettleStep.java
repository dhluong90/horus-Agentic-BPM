package io.github.dhluong90.bpm.process.internal.exec;

/**
 * A rule that can only be decided once every token of one apply has moved as far as it goes, such as an inclusive
 * join, which must see where the other tokens are. The kernel runs every step each time its agenda is empty, and
 * again until none changes anything. Steps mutate state only through the {@link ExecutionContext}.
 */
public interface SettleStep {

    /**
     * @param ctx the execution context of the current apply
     * @return whether the step changed the instance, so that the kernel drains its agenda and settles again
     */
    boolean settle(ExecutionContext ctx);
}
