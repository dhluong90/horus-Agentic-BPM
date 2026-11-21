package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.PersonalDataNames;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionDiff.Change;
import io.github.dhluong90.bpm.rules.api.VersionDiff.ColumnChange;
import io.github.dhluong90.bpm.rules.api.VersionDiff.ColumnChanges;
import io.github.dhluong90.bpm.rules.api.VersionDiff.FieldChange;
import io.github.dhluong90.bpm.rules.api.VersionDiff.InputMasking;
import io.github.dhluong90.bpm.rules.api.VersionDiff.Masking;
import io.github.dhluong90.bpm.rules.api.VersionDiff.RowChange;
import io.github.dhluong90.bpm.expr.internal.Expr;
import io.github.dhluong90.bpm.expr.internal.Parser;
import io.github.dhluong90.bpm.rules.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * Computes the row-level diff of two versions of one rule set from their canonical documents (DESIGN section 4.5,
 * RLE-028, RLE-048). A pure function: no I/O, no clock, and the same inputs always give an equal result, so a repeated
 * request returns a byte-identical body.
 *
 * <p>Rows are matched by rule id. The moved set is the common rows outside the longest common subsequence of the two
 * orders; because rule ids are unique within a version, that subsequence is the longest increasing subsequence of the
 * common rows' base positions taken in the new order, found in O(n log n). Among several longest subsequences the one
 * that keeps the earliest rows of the new version wins, so inserting or removing rows never moves the rows around
 * them and a swapped pair reports the later row as moved.
 */
final class VersionDiffs {

    /**
     * Document members that are not header fields: compared separately, equal by construction, or version metadata
     * outside the behaviour hash ({@code effectiveFrom}, reported beside the content diff).
     */
    private static final Set<String> NOT_HEADER = Set.of("inputs", "outputs", "rules", "ruleSet", "effectiveFrom");

    private VersionDiffs() {
    }

    /**
     * Compares {@code version} against {@code base}.
     *
     * @param version the version compared
     * @param base the version it is compared against; the same rule set
     * @return the diff
     */
    static VersionDiff diff(StoredVersion version, StoredVersion base) {
        return diff(version, base, false, PersonalDataNames.of(List.of()));
    }

    /**
     * Compares {@code version} against {@code base}, with each input's masking status (RLE-051).
     *
     * @param version the version compared
     * @param base the version it is compared against; the same rule set
     * @param maskByDefault the tenant's {@code decision-log.mask-default}, for inputs that declare no {@code mask}
     * @param names the tenant's personal-data names, to mark an undeclared look-alike in {@code version}
     * @return the diff
     */
    static VersionDiff diff(StoredVersion version, StoredVersion base, boolean maskByDefault,
            PersonalDataNames names) {
        JsonNode v = EngineValues.read(version.canonicalJson());
        JsonNode w = EngineValues.read(base.canonicalJson());
        List<FieldChange> header = members(w, v, "", NOT_HEADER, false);
        ColumnChanges inputs = columns(w.path("inputs"), v.path("inputs"), false);
        ColumnChanges outputs = columns(w.path("outputs"), v.path("outputs"), true);
        Rows rows = rows(w.path("rules"), v.path("rules"));
        return new VersionDiff(version.ruleSet(), version.version(), base.version(), version.canonicalHash(),
                base.canonicalHash(), !version.sourceHash().equals(base.sourceHash()), version.effectiveFrom(),
                base.effectiveFrom(), header, inputs, outputs, rows.changes(), rows.unchanged(),
                masking(version.canonicalJson(), base.canonicalJson(), maskByDefault, names));
    }

    // ---------------------------------------------------------------- masking

    /** Every input of either version with its status in both; inputs of {@code version} first, in its order. */
    private static List<InputMasking> masking(String version, String base, boolean maskByDefault,
            PersonalDataNames names) {
        Map<String, Masking> before = new LinkedHashMap<>();
        MaskDeclarations.statuses(base, maskByDefault).forEach(s -> before.put(s.input(), s.masking()));
        List<InputMasking> result = new ArrayList<>();
        for (MaskDeclarations.Status now : MaskDeclarations.statuses(version, maskByDefault)) {
            Masking from = before.remove(now.input());
            String lookAlike = now.masking().declared() ? null : names.match(now.input()).orElse(null);
            result.add(new InputMasking(now.input(), from, now.masking(), from != now.masking(), lookAlike));
        }
        before.forEach((input, from) -> result.add(new InputMasking(input, from, null, true, null)));
        return result;
    }

    // ---------------------------------------------------------------- rows

    private record Rows(List<RowChange> changes, int unchanged) {
    }

    private static Rows rows(JsonNode baseRules, JsonNode newRules) {
        List<JsonNode> w = list(baseRules);
        List<JsonNode> v = list(newRules);
        Map<String, Integer> basePosition = new HashMap<>();
        for (int i = 0; i < w.size(); i++) {
            basePosition.put(id(w.get(i)), i);
        }
        Set<String> newIds = new TreeSet<>();
        int common = 0;
        for (JsonNode row : v) {
            newIds.add(id(row));
            if (basePosition.containsKey(id(row))) {
                common++;
            }
        }
        // The base positions of the common rows, in the new order; the rows on the kept subsequence are not moved.
        int[] sequence = new int[common];
        int k = 0;
        for (JsonNode row : v) {
            Integer at = basePosition.get(id(row));
            if (at != null) {
                sequence[k++] = at;
            }
        }
        boolean[] kept = earliestLongestIncreasing(sequence);

        List<RowChange> changes = new ArrayList<>();
        int unchanged = 0;
        k = 0;
        for (int i = 0; i < v.size(); i++) {
            JsonNode row = v.get(i);
            Integer at = basePosition.get(id(row));
            if (at == null) {
                changes.add(new RowChange(id(row), Change.ADDED, i + 1, null, false, List.of()));
                continue;
            }
            boolean moved = !kept[k++];
            List<FieldChange> fields = rowFields(w.get(at), row);
            if (!fields.isEmpty()) {
                changes.add(new RowChange(id(row), Change.CHANGED, i + 1, at + 1, moved, fields));
            } else if (moved) {
                changes.add(new RowChange(id(row), Change.MOVED, i + 1, at + 1, false, List.of()));
            } else {
                unchanged++;
            }
        }
        for (int j = 0; j < w.size(); j++) {
            if (!newIds.contains(id(w.get(j)))) {
                changes.add(new RowChange(id(w.get(j)), Change.REMOVED, null, j + 1, false, List.of()));
            }
        }
        return new Rows(changes, unchanged);
    }

    /**
     * Marks one longest strictly increasing subsequence of distinct values: the one whose positions are earliest in
     * lexicographic order.
     *
     * @param a distinct values
     * @return for each position, whether it is on the subsequence
     */
    static boolean[] earliestLongestIncreasing(int[] a) {
        int n = a.length;
        // longestFrom[i]: length of the longest increasing subsequence that starts at i, measured right to left with
        // patience sorting over the negated values (an increasing run to the right is a decreasing run read leftwards).
        int[] longestFrom = new int[n];
        int[] tails = new int[n];
        int size = 0;
        for (int i = n - 1; i >= 0; i--) {
            int x = -a[i];
            int slot = Arrays.binarySearch(tails, 0, size, x);
            if (slot < 0) {
                slot = -slot - 1;
            }
            tails[slot] = x;
            if (slot == size) {
                size++;
            }
            longestFrom[i] = slot + 1;
        }
        boolean[] kept = new boolean[n];
        int need = size;
        long last = Long.MIN_VALUE;
        for (int i = 0; i < n && need > 0; i++) {
            if (a[i] > last && longestFrom[i] == need) {
                kept[i] = true;
                last = a[i];
                need--;
            }
        }
        return kept;
    }

    /** Cells by column under {@code when} and {@code then}, other row members whole; in canonical member order. */
    private static List<FieldChange> rowFields(JsonNode from, JsonNode to) {
        List<FieldChange> fields = new ArrayList<>();
        for (String member : union(from, to)) {
            if (member.equals("id")) {
                continue;
            }
            JsonNode a = from.get(member);
            JsonNode b = to.get(member);
            if ((member.equals("when") || member.equals("then") || member.equals("explain")) && isObjectOrAbsent(a) && isObjectOrAbsent(b)) {
                JsonNode left = a == null ? EngineValues.read("{}") : a;
                JsonNode right = b == null ? EngineValues.read("{}") : b;
                fields.addAll(members(left, right, "/" + member, Set.of(), member.equals("then")));
            } else if (!same(a, b)) {
                fields.add(field("/" + pointer(member), a, b, false));
            }
        }
        return fields;
    }

    // ---------------------------------------------------------------- columns and header

    private static ColumnChanges columns(JsonNode baseColumns, JsonNode newColumns, boolean outputs) {
        Map<String, JsonNode> w = byName(baseColumns);
        Map<String, JsonNode> v = byName(newColumns);
        List<String> added = new ArrayList<>();
        List<ColumnChange> changed = new ArrayList<>();
        List<String> commonInNewOrder = new ArrayList<>();
        for (Map.Entry<String, JsonNode> column : v.entrySet()) {
            JsonNode before = w.get(column.getKey());
            if (before == null) {
                added.add(column.getKey());
                continue;
            }
            commonInNewOrder.add(column.getKey());
            List<FieldChange> fields = members(before, column.getValue(), "", Set.of("name"), outputs);
            if (!fields.isEmpty()) {
                changed.add(new ColumnChange(column.getKey(), fields));
            }
        }
        List<String> removed = new ArrayList<>();
        List<String> commonInBaseOrder = new ArrayList<>();
        for (String name : w.keySet()) {
            if (v.containsKey(name)) {
                commonInBaseOrder.add(name);
            } else {
                removed.add(name);
            }
        }
        return new ColumnChanges(added, removed, changed, !commonInNewOrder.equals(commonInBaseOrder));
    }

    /**
     * The changed members of two objects, in canonical (code point) order.
     *
     * @param moneyCells whether a member value that is one money literal is shown as money: {@code then} cells and
     *     output columns (their {@code default})
     */
    private static List<FieldChange> members(JsonNode from, JsonNode to, String prefix, Set<String> skip,
            boolean moneyCells) {
        List<FieldChange> fields = new ArrayList<>();
        for (String member : union(from, to)) {
            if (skip.contains(member)) {
                continue;
            }
            JsonNode a = from.get(member);
            JsonNode b = to.get(member);
            if (!same(a, b)) {
                fields.add(field(prefix + "/" + pointer(member), a, b, moneyCells));
            }
        }
        return fields;
    }

    // ---------------------------------------------------------------- values

    private static FieldChange field(String path, JsonNode from, JsonNode to, boolean moneyCells) {
        return new FieldChange(path, value(from, moneyCells), value(to, moneyCells),
                nonAscii(from) || nonAscii(to));
    }

    /** Canonical JSON text of a value; a single money literal becomes {@code {currency, amount}} in minor units. */
    private static String value(JsonNode node, boolean moneyCells) {
        if (node == null) {
            return null;
        }
        if (moneyCells && node.isString()) {
            Money money = moneyLiteral(node.stringValue());
            if (money != null) {
                return EngineValues.write(EngineValues.value(money));
            }
        }
        return CanonicalJson.write(node);
    }

    private static Money moneyLiteral(String cell) {
        try {
            return Parser.parse(cell, name -> -1) instanceof Expr.Literal literal && literal.value() instanceof Money money
                    ? money : null;
        } catch (RuntimeException notALiteral) {
            return null;
        }
    }

    /**
     * Whether the value's text holds a character outside printable ASCII, or an escape the canonical printer writes for
     * a control or format character ({@code \\u} followed by four hex digits), so a look-alike is visible to the
     * approver (ADR-0026 decision 6).
     */
    static boolean nonAscii(JsonNode node) {
        if (node == null) {
            return false;
        }
        String text = node.isString() ? node.stringValue() : CanonicalJson.write(node);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return true;
            }
            if (c == '\\' && i + 1 < text.length() && text.charAt(i + 1) == 'u' && hex4(text, i + 2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hex4(String text, int from) {
        if (from + 4 > text.length()) {
            return false;
        }
        for (int i = from; i < from + 4; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private static boolean same(JsonNode a, JsonNode b) {
        return a == null ? b == null : a.equals(b);
    }

    private static boolean isObjectOrAbsent(JsonNode node) {
        return node == null || node.isObject();
    }

    private static TreeSet<String> union(JsonNode a, JsonNode b) {
        TreeSet<String> names = new TreeSet<>();
        if (a != null && a.isObject()) {
            names.addAll(a.propertyNames());
        }
        if (b != null && b.isObject()) {
            names.addAll(b.propertyNames());
        }
        return names;
    }

    private static Map<String, JsonNode> byName(JsonNode columns) {
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode column : list(columns)) {
            byName.put(column.path("name").stringValue(), column);
        }
        return byName;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(items::add);
        }
        return items;
    }

    private static String id(JsonNode row) {
        return row.path("id").stringValue();
    }

    /** RFC 6901 escaping of one reference token. */
    private static String pointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
