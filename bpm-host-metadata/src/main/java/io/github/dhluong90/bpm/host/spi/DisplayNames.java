package io.github.dhluong90.bpm.host.spi;

import java.util.Locale;
import java.util.Optional;

/**
 * Readable, localised names for the roles, groups and users the engines show by identifier (#442): a task's
 * {@code role:CARD_OFFICER}, a group, or the user who requested an approval. The engines' default reads
 * {@code horus.bpm.display-names.roles}, {@code .groups} and {@code .users}, each a map of identifier to a map of
 * language to name. A host whose directory knows its people declares its own bean of this type, which replaces the
 * default:
 * <pre>{@code
 * @Bean
 * DisplayNames bankDirectoryNames(BankDirectory directory) {
 *     return new DisplayNames() {
 *         @Override
 *         public Optional<String> user(String tenantId, String userId, Locale locale) {
 *             return directory.fullName(tenantId, userId);
 *         }
 *     };
 * }
 * }</pre>
 *
 * <p>Every method has a default that knows no name, so a host overrides only what it has. An empty answer means the
 * caller shows the identifier itself. Names are labels only: no permission, assignment or check ever reads them.
 * Implementations must be thread-safe, fast and must not perform slow network I/O: they run on the request thread,
 * once per identifier of a response.
 */
public interface DisplayNames {

    /**
     * @param tenantId the caller's tenant
     * @param role the role identifier as the engines use it, for example {@code CARD_OFFICER}
     * @param locale the caller's language, from {@code Accept-Language}
     * @return the role's name in that language, or empty to show the identifier
     */
    default Optional<String> role(String tenantId, String role, Locale locale) {
        return Optional.empty();
    }

    /**
     * @param tenantId the caller's tenant
     * @param group the group identifier, for example {@code hn-branch-tellers}
     * @param locale the caller's language
     * @return the group's name in that language, or empty to show the identifier
     */
    default Optional<String> group(String tenantId, String group, Locale locale) {
        return Optional.empty();
    }

    /**
     * @param tenantId the caller's tenant
     * @param userId the user identifier, as the engines record it
     * @param locale the caller's language
     * @return the person's name, or empty to show the identifier
     */
    default Optional<String> user(String tenantId, String userId, Locale locale) {
        return Optional.empty();
    }

    /**
     * The name the tenant switcher shows for a tenant the caller may select (#474), distinct from its id.
     * {@code GET /api/bpm/me} asks only for the tenants in the caller's allowed list, never any other.
     *
     * @param tenantId an allowed tenant of the caller
     * @param locale the caller's language
     * @return the tenant's name, or empty to show the tenant id
     * @since 0.3.0
     */
    default Optional<String> tenant(String tenantId, Locale locale) {
        return Optional.empty();
    }

    /** @return names that are never known: every caller shows identifiers */
    static DisplayNames none() {
        return new DisplayNames() {
        };
    }
}
