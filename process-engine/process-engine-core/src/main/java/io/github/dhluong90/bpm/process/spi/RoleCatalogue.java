package io.github.dhluong90.bpm.process.spi;

/**
 * Answers whether a role exists in a tenant's role list, so a process definition whose escalation names an unknown
 * role is refused at approval instead of escalating an overdue task to a queue nobody watches (PRE-180, #449). The
 * engine does not hold roles: the host's identity provider does.
 *
 * <p>The engine asks at request-approval and again at approval, for every {@code role:} target of a
 * {@code horus:escalation} level, with the tenant of the caller. Implementations must be thread-safe, must answer for
 * the given tenant only (one tenant's roles never satisfy another's), and must not throw: a role the catalogue cannot
 * find does not exist.
 *
 * <p>The default accepts every role, so a host that has not supplied a catalogue is not checked; the starter logs a
 * warning at start-up outside the {@code dev} profile. A host that wants the check registers a bean of this type.
 */
public interface RoleCatalogue {

    /** Lists every role: the default, which checks nothing. */
    RoleCatalogue ACCEPT_ALL = (tenantId, role) -> true;

    /**
     * @param tenantId the tenant whose role list is asked
     * @param role a role name without a {@code ROLE_} prefix, as written after {@code role:} in an escalation target
     * @return whether the role is in that tenant's role list
     */
    boolean exists(String tenantId, String role);

    /**
     * Lists the roles of a tenant for the step catalogue (PRE-032), so a modeller picks only roles the host knows. The
     * default lists none, which the catalogue shows as no roles offered; a host that wants them listed overrides it.
     * It must answer for the given tenant only and must not throw.
     *
     * @param tenantId the tenant whose roles are listed
     * @return the role names without a {@code ROLE_} prefix, at most a few hundred
     */
    default java.util.List<String> roles(String tenantId) {
        return java.util.List.of();
    }
}
