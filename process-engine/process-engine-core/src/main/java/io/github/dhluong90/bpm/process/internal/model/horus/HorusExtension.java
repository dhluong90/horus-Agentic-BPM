package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * Typed HORUS extension values read from the {@code horus} namespace (ADR-0018).
 */
public sealed interface HorusExtension
        permits HandlerRef, DecisionRef, TaskAssignment, InputMapping, ResultVariable, FormSpec, SlaSpec, EscalationSpec, AgentTaskSpec, GuardrailSpec, CompensateHandlerRef, AsyncMode, MoneyType, RetryPolicy, MemoryEntry, OnFailure, OutputMapping, CalledVersion, CorrelationKey {
}
