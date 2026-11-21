package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.expr.spi.FunctionLibrary;
import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The host's functions for rule cells (RLE-055 to RLE-058): the library, the record of the versions each rule set
 * version was approved with, and what a changed function version does (PD-119). Functions are registered per host,
 * never per tenant.
 *
 * @param library the registered functions
 * @param approvals the function versions each approved rule set version was approved with
 * @param onVersionChange what evaluation does when a function's version differs from the approved one
 */
public record HostFunctions(FunctionLibrary library, FunctionVersionStore approvals, VersionChange onVersionChange) {

    /**
     * {@code horus.bpm.rules.evaluation.function-version-change}: {@code warn} (default) evaluates on and records the
     * version used; {@code refuse} fails the decision closed with {@code FUNCTION_CHANGED} until a new version is
     * approved under four eyes.
     */
    public enum VersionChange {
        /** Evaluation goes on. */
        WARN,
        /** Evaluation fails closed. */
        REFUSE;

        /**
         * @param text {@code warn} or {@code refuse}, or null for the default
         * @return the value
         * @throws IllegalArgumentException naming the setting
         */
        public static VersionChange parse(String text) {
            if (text == null || text.equals("warn")) {
                return WARN;
            }
            if (text.equals("refuse")) {
                return REFUSE;
            }
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.function-version-change must be warn or"
                    + " refuse, was '" + text + "'");
        }
    }

    public HostFunctions {
        Objects.requireNonNull(library, "library must not be null");
        Objects.requireNonNull(approvals, "approvals must not be null");
        Objects.requireNonNull(onVersionChange, "onVersionChange must not be null");
    }

    /** @return no function, an in-memory record and the default {@code warn} */
    public static HostFunctions none() {
        return new HostFunctions(FunctionLibrary.empty(), FunctionVersionStore.inMemory(), VersionChange.WARN);
    }

    /**
     * @param functions the functions the host registered
     * @param approvals where approved versions record what they were approved with
     * @param onVersionChange the policy
     * @return the holder
     */
    public static HostFunctions of(Collection<ExpressionFunction> functions, FunctionVersionStore approvals,
            VersionChange onVersionChange) {
        return new HostFunctions(FunctionLibrary.of(functions), approvals, onVersionChange);
    }

    /**
     * @param names function names
     * @return the registered version label of each, in name order; a name that is not registered is absent
     */
    SortedMap<String, String> currentVersions(Collection<String> names) {
        SortedMap<String, String> versions = new TreeMap<>();
        for (String name : names) {
            library.find(name).ifPresent(function -> versions.put(name, function.version()));
        }
        return versions;
    }

    /**
     * Writes version labels as the decision row keeps them: {@code name@version}, comma separated, in name order.
     *
     * @param versions function name to version label
     * @return the text, or null when there is none
     */
    static String format(Map<String, String> versions) {
        if (versions.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        new TreeMap<>(versions).forEach((name, label) -> text.append(text.isEmpty() ? "" : ",").append(name).append('@')
                .append(label));
        return text.toString();
    }

    /**
     * Reads what {@link #format} wrote.
     *
     * @param text the decision row's text, or null
     * @return function name to version label
     */
    static Map<String, String> parse(String text) {
        Map<String, String> versions = new TreeMap<>();
        if (text != null && !text.isEmpty()) {
            for (String part : text.split(",")) {
                int at = part.indexOf('@');
                versions.put(part.substring(0, at), part.substring(at + 1));
            }
        }
        return versions;
    }
}
