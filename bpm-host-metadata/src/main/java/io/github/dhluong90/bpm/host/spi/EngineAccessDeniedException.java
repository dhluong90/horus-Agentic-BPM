package io.github.dhluong90.bpm.host.spi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * {@link EngineAccess} refused a request: no authenticated caller, no tenant, a tenant the caller is not
 * signed in for, or a missing permission. The host metadata module answers it with an RFC 9457 problem
 * carrying {@link #code()} (ADR-0010); an engine controller that writes its own problems uses
 * {@link #status()}, {@link #code()}, {@link #challenge()} and {@link #correlationId()}.
 *
 * <p>Carries no stack trace and no caller data, so it can never leak either into a response (TR-SEC-08).
 *
 * @since 0.1.0
 */
public final class EngineAccessDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the request was refused. */
    public enum Reason {
        /** No authenticated principal: {@code 401 UNAUTHENTICATED}. */
        UNAUTHENTICATED(401, "UNAUTHENTICATED"),
        /** A principal for whom no tenant could be resolved: {@code 401 UNAUTHENTICATED}. */
        NO_TENANT(401, "UNAUTHENTICATED"),
        /** A tenant the caller is not signed in for, or outside the allowed format: {@code 403 TENANT_NOT_ALLOWED}. */
        TENANT_NOT_ALLOWED(403, "TENANT_NOT_ALLOWED"),
        /** The caller lacks the permission the endpoint checks: {@code 403 PERMISSION_DENIED}. */
        PERMISSION_DENIED(403, "PERMISSION_DENIED");

        private final int status;
        private final String code;

        Reason(int status, String code) {
            this.status = status;
            this.code = code;
        }

        /** The HTTP status of the refusal. */
        public int status() {
            return status;
        }

        /** The error code of ADR-0010, for example {@code TENANT_NOT_ALLOWED}. */
        public String code() {
            return code;
        }
    }

    private final Reason reason;
    private final @Nullable String challenge;
    private final @Nullable String correlationId;

    /**
     * A refusal.
     *
     * @param reason why the request was refused
     * @param challenge the {@code WWW-Authenticate} value for a {@code 401}, or {@code null}
     * @param correlationId the correlation id of the security log entry written for the refusal, or
     *     {@code null} when none was written
     */
    public EngineAccessDeniedException(Reason reason, @Nullable String challenge, @Nullable String correlationId) {
        super(Objects.requireNonNull(reason, "reason").code(), null, false, false);
        this.reason = reason;
        this.challenge = challenge;
        this.correlationId = correlationId;
    }

    /** Why the request was refused. */
    public Reason reason() {
        return reason;
    }

    /** The HTTP status: {@code 401} or {@code 403}. */
    public int status() {
        return reason.status();
    }

    /** The error code of ADR-0010: {@code UNAUTHENTICATED}, {@code TENANT_NOT_ALLOWED} or {@code PERMISSION_DENIED}. */
    public String code() {
        return reason.code();
    }

    /** The {@code WWW-Authenticate} value to send with a {@code 401}, or {@code null}. */
    public @Nullable String challenge() {
        return challenge;
    }

    /** The correlation id of the security log entry for this refusal, or {@code null} when none was written. */
    public @Nullable String correlationId() {
        return correlationId;
    }
}
