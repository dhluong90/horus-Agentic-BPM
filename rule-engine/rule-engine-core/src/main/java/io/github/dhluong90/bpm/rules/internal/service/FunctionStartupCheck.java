package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The start-up check of the host functions (RLE-058, PD-119): which functions were approved with another version
 * label than the one registered now, and which published versions call them. Run after the migrations, once. A name
 * registered twice, a built-in name or an invalid name has already stopped the host when the library was built.
 */
public final class FunctionStartupCheck {

    private final HostFunctions functions;

    FunctionStartupCheck(HostFunctions functions) {
        this.functions = Objects.requireNonNull(functions, "functions must not be null");
    }

    /**
     * A function whose version label is not the one published versions were approved with.
     *
     * @param function the function name
     * @param approvedVersion the label the versions were approved with
     * @param currentVersion the label registered now; null when the function is no longer registered
     * @param uses the published versions that call it, with that label
     */
    public record Change(String function, String approvedVersion, String currentVersion,
            List<FunctionVersionStore.Use> uses) {

        /** @return the published versions as {@code tenant/ruleSet vN}, for the log */
        public List<String> describedUses() {
            return uses.stream().map(use -> use.tenantId() + "/" + use.ruleSet() + " v" + use.version()).toList();
        }
    }

    /**
     * Reads the recorded uses of published versions and compares them with the registry.
     *
     * @return the changes, in function and label order; empty when every function has its approved label
     */
    public List<Change> changes() {
        Map<String, List<FunctionVersionStore.Use>> grouped = new LinkedHashMap<>();
        for (FunctionVersionStore.Use use : functions.approvals().publishedUses()) {
            String current = functions.library().find(use.function()).map(f -> f.version()).orElse(null);
            if (!use.functionVersion().equals(current)) {
                grouped.computeIfAbsent(use.function() + "\u0000" + use.functionVersion(), k -> new ArrayList<>())
                        .add(use);
            }
        }
        List<Change> changes = new ArrayList<>();
        grouped.forEach((key, uses) -> {
            String function = uses.getFirst().function();
            changes.add(new Change(function, uses.getFirst().functionVersion(),
                    functions.library().find(function).map(f -> f.version()).orElse(null), List.copyOf(uses)));
        });
        return List.copyOf(changes);
    }
}
