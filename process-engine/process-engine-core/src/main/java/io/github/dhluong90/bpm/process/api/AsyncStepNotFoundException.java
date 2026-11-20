package io.github.dhluong90.bpm.process.api;

/** No async step job with this id in the caller's tenant. Code {@code ASYNC_STEP_NOT_FOUND}. */
public final class AsyncStepNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public AsyncStepNotFoundException(String message) {
        super("ASYNC_STEP_NOT_FOUND", message);
    }
}
