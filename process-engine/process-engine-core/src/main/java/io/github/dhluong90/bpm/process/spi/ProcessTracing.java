package io.github.dhluong90.bpm.process.spi;

/**
 * Receives the engine's trace points (#114, TR-OBS-03): one span for each step the execution kernel applies, from the
 * moment the engine starts it, so a decision evaluated by the step is a child. A call carries engine values only: a
 * tenant, a definition key and version, a step kind, an outcome and an engine error code. It never carries a business
 * key, an instance id, a variable, an input or an output, so an implementation cannot leak one into a span
 * (TR-SEC-05). Implementations must not throw; one that does is ignored. The default does nothing.
 *
 * <p>The starter binds an implementation to the host's Micrometer {@code Tracer} when the host has one.
 *
 * <p>An async step crosses transactions and often threads and processes: the step that schedules the call ends, and
 * the result is applied later, in a trace of its own. The engine keeps the scheduling span's {@link Span#link()} in the
 * job and hands it back as {@code linkedFrom} when it starts the span that applies the result, so the tracer can draw a
 * span link between the two. The link names ids only: a trace id and a span id.
 */
public interface ProcessTracing {

    /** Does nothing and allocates nothing. */
    ProcessTracing NONE = new ProcessTracing() {
        @Override
        public Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind) {
            return Span.NONE;
        }
    };

    /**
     * A step is about to run.
     *
     * @param tenantId the tenant
     * @param definitionKey the process key
     * @param definitionVersion the definition version
     * @param stepKind the command kind, for example {@code START}, {@code COMPLETE_TASK}, {@code JOB_DUE} or
     *     {@code LOCAL_CALL}
     * @return the span; {@link Span#end} is called exactly once
     */
    Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind);

    /**
     * A step is about to run, as the continuation of an earlier span: the result of an async service or agent call.
     * The default ignores the link and starts a plain step span, so an implementation written before links existed
     * keeps working.
     *
     * @param tenantId the tenant
     * @param definitionKey the process key
     * @param definitionVersion the definition version
     * @param stepKind the command kind, for example {@code ASYNC_RESULT}
     * @param linkedFrom the {@link Span#link()} of the span that scheduled the call, never null here
     * @return the span; {@link Span#end} is called exactly once
     */
    default Span startStep(String tenantId, String definitionKey, int definitionVersion, String stepKind,
            String linkedFrom) {
        return startStep(tenantId, definitionKey, definitionVersion, stepKind);
    }

    /** One step span. */
    interface Span {

        /** Does nothing. */
        Span NONE = new Span() {
            @Override
            public void end(String outcome, String errorCode, Throwable failure) {
            }
        };

        /**
         * Names this span so that a later span can link to it: its trace id and span id, as an opaque string made of
         * ids only. The engine stores it in the async job and returns it to
         * {@link ProcessTracing#startStep(String, String, int, String, String)}. Valid after {@link #end} too.
         *
         * @return the reference, or null when the span cannot be linked to (the default)
         */
        default String link() {
            return null;
        }

        /**
         * The step ended.
         *
         * @param outcome {@code OK}, {@code FAILED} or {@code SKIPPED}
         * @param errorCode the engine error code of a failed step, or null
         * @param failure the exception of a failed step, or null; use its class only, never its message
         */
        void end(String outcome, String errorCode, Throwable failure);
    }
}
