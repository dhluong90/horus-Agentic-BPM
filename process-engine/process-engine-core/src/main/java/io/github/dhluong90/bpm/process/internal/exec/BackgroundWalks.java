package io.github.dhluong90.bpm.process.internal.exec;

/**
 * The marker of a background compensation (PD-149, {@code waitForCompletion="false"}). A walk of the undo stack that
 * runs beside the flow is a live token of kind {@code FLOW} that carries this local; its value is the activity the
 * walk undoes, or the empty string for the whole scope. The token keeps the instance open until the walk is done.
 */
public final class BackgroundWalks {

    /** The token local that marks a background walk. */
    public static final String MARK = "$backgroundUndo";

    private BackgroundWalks() {
    }
}
