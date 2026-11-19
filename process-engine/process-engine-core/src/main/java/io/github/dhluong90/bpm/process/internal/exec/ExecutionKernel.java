package io.github.dhluong90.bpm.process.internal.exec;

import java.time.Clock;

/**
 * The functional core of the Process Engine: state, command and clock in; new state, effects and history out.
 *
 * <p>Rule of the contract: the kernel performs no network, adapter or database call and reads no clock other than
 * the one passed in. Every external action is an {@link Effect}. A kernel instance is bound to one compiled
 * definition version ({@link CompiledProcess}) and is safe for concurrent use.
 */
public interface ExecutionKernel {

    /**
     * Applies a command.
     *
     * @param state the current state of the instance
     * @param command the command
     * @param clock the time source for history and due dates
     * @return the transition
     * @throws NotExecutableException when the command reaches a node with no registered behaviour
     * @throws ExecutionException when the command does not apply to the state (for example a completed token)
     */
    Transition apply(InstanceState state, Command command, Clock clock);
}
