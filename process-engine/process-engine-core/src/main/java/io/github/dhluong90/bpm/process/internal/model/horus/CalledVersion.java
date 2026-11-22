package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:calledVersion} of a {@code callProcess} step (#92, PRE-204): the child always starts on this version of
 * {@code calledElement}, even when a later one is effective. Without it the version effective when the step runs is used.
 *
 * @param version the version number, 1 or more
 */
public record CalledVersion(int version) implements HorusExtension {

    public CalledVersion {
        if (version < 1) {
            throw new IllegalArgumentException("horus:calledVersion must be 1 or more");
        }
    }
}
