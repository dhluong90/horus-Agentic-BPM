package com.horus.bpm.host.autoconfigure;

/** Carries a {@link Problem} from caller resolution to the controller's error handler. */
final class ProblemException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Problem problem;

    ProblemException(Problem problem) {
        super(problem.code(), null, false, false);
        this.problem = problem;
    }

    Problem problem() {
        return problem;
    }
}
