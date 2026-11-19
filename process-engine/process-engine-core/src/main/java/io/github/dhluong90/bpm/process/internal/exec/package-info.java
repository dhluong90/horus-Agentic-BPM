/**
 * Execution kernel contract (C3): a functional core that turns (state, command, clock) into (state, effects,
 * history) with no I/O. Behaviours plug in per node type through {@link BehaviorModule}s listed in
 * {@link BehaviorCatalog}.
 */
package io.github.dhluong90.bpm.process.internal.exec;
