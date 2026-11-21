package io.github.dhluong90.bpm.rules.api;

/** {@code 422 BATCH_TOO_LARGE}: a batch holds more items than {@code evaluation.max-batch} (RLE-068). */
public final class BatchTooLargeException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param items the number of items sent
     * @param limit {@code evaluation.max-batch}
     */
    public BatchTooLargeException(int items, int limit) {
        super("BATCH_TOO_LARGE", "A batch holds at most " + limit + " items (evaluation.max-batch), was " + items);
    }
}
