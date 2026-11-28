package io.github.dhluong90.bpm.process.spi;

/**
 * Receives the engine's trace points (#114, TR-OBS-03): one span for each step the execution kernel applies, from the
 * moment the engine starts it, so a decision evaluated by the step is a child. A call carries engine values only: a
 * tenant, a definition key and version, a step kind, an outcome and an engine error code. It never carries a business
 * key, an instance id, a variable, an input or an output, so an implementation cannot leak one into a span
 * (TR-SEC-05). Implementations must not throw; one that does is ignored. The default does nothing.
 *
 * <p>The starter binds an implementation to the host's Micrometer {@code Tracer} when the host has one.
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

    /** One step span. */
    interface Span {

        /** Does nothing. */
        Span NONE = new Span() {
            @Override
            public void end(String outcome, String errorCode, Throwable failure) {
            }
        };

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
