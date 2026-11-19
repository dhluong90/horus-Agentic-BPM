package com.horus.bpm.host.autoconfigure;

import org.jspecify.annotations.Nullable;

/** Carries a {@link Problem}, and the {@code WWW-Authenticate} value of a {@code 401}, to the error handler. */
final class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Problem problem;
    private final @Nullable String challenge;

    ProblemException(Problem problem) {
        this(problem, null);
    }

    ProblemException(Problem problem, @Nullable String challenge) {
        super(problem.code(), null, false, false);
        this.problem = problem;
        this.challenge = challenge;
    }

    Problem problem() {
        return problem;
    }

    @Nullable
    String challenge() {
        return challenge;
    }
}
