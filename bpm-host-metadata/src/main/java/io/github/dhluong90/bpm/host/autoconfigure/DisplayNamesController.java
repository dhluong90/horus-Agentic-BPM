package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.DisplayNames;
import io.github.dhluong90.bpm.host.spi.EngineAccess;
import io.github.dhluong90.bpm.host.spi.EngineCaller;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/bpm/display-names} (#442): the readable names, in the caller's language, of the roles, groups and
 * users a screen shows by identifier, for example the role of a task in the queue or the requester of an approval.
 * Only names the host's {@link DisplayNames} knows are returned; a client shows the identifier for the rest. Needs an
 * authenticated caller and no other permission; the tenant is the caller's, from {@link EngineAccess}. The language
 * is the first of {@code Accept-Language}. Names are labels only and grant nothing.
 */
@RestController
@RequestMapping("/api/bpm")
final class DisplayNamesController {

    /** Most identifiers of one kind in one request. */
    static final int MAX_IDS = 100;
    /** An identifier as the engines record it. */
    static final Pattern ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$");
    static final String CACHE_CONTROL = "private, no-cache";

    private final EngineAccess access;
    private final DisplayNames names;

    DisplayNamesController(EngineAccess access, DisplayNames names) {
        this.access = access;
        this.names = names;
    }

    @GetMapping("/display-names")
    ResponseEntity<?> displayNames(HttpServletRequest request,
            @RequestParam(value = "roles", required = false) List<String> roles,
            @RequestParam(value = "groups", required = false) List<String> groups,
            @RequestParam(value = "users", required = false) List<String> users) {
        EngineCaller caller = access.currentCaller();
        Locale locale = request.getLocale();
        Optional<String> invalid = invalid("roles", roles).or(() -> invalid("groups", groups))
                .or(() -> invalid("users", users));
        if (invalid.isPresent()) {
            String problem = new io.github.dhluong90.bpm.host.internal.Problem(400, "INPUT_INVALID", "Bad Request",
                    invalid.get()).toJson(request);
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String tenant = caller.tenant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", locale.getLanguage().isEmpty() ? "und" : locale.getLanguage());
        body.put("roles", known(ids(roles), id -> names.role(tenant, id, locale)));
        body.put("groups", known(ids(groups), id -> names.group(tenant, id, locale)));
        body.put("users", known(ids(users), id -> names.user(tenant, id, locale)));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .header(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE, HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE,
                        DefaultEngineAccess.TENANT_SWITCH_HEADER)
                .body(body);
    }

    private static Optional<String> invalid(String parameter, List<String> values) {
        List<String> ids = ids(values);
        if (ids.size() > MAX_IDS) {
            return Optional.of(parameter + " lists more than " + MAX_IDS + " identifiers");
        }
        return ids.stream().filter(id -> !ID.matcher(id).matches()).findFirst()
                .map(id -> parameter + " holds an identifier that is not 1 to 128 letters, digits or . _ : @ -");
    }

    /** The distinct, trimmed identifiers in request order; a comma-separated value counts as several. */
    private static List<String> ids(List<String> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            for (String id : value.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static Map<String, String> known(List<String> ids,
            java.util.function.Function<String, Optional<String>> lookup) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String id : ids) {
            Optional<String> name;
            try {
                name = lookup.apply(id);
            } catch (RuntimeException e) {
                // A host directory that fails leaves the identifier to show; it never breaks the screen
                name = Optional.empty();
            }
            name.filter(n -> !n.isBlank()).ifPresent(n -> found.put(id, n));
        }
        return found;
    }
}
