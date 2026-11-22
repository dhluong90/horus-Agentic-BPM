package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Op;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Test;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.UnaryTests;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Value;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.model.ValueType;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The spreadsheet form of a rule table (PD-107, PD-142): a CSV file with one header line and one line per rule.
 * Columns are {@code Rule}, each input by name, each output by name, then {@code Description}. Cells use a
 * shorthand that business staff write in Excel: {@code GOLD, PLATINUM}, {@code >= 50000000 VND}, {@code 18 to 65},
 * {@code != GOLD}, {@code -} for any. Cells go through {@link V1Forms}, so a table written here has the same
 * normal forms, and the same canonical hash, as the same table imported from YAML or DMN.
 *
 * <p>Reading never produces another value from a damaged cell: scientific notation, thousands separators, dates
 * that are not ISO dates and text in a number column are refused, each with the rule and the column. No message
 * quotes a cell, so a value of a masked input never leaves in an error.
 */
public final class CsvTable {

    /** Most problems listed before the list is cut. */
    public static final int MAX_PROBLEMS = 100;

    private static final String BOM = "﻿";
    private static final Pattern RULE_ID = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Pattern NUMBER = Pattern.compile("^-?[0-9]{1,30}(\\.[0-9]{1,30})?$");
    private static final Pattern THOUSANDS_COMMA = Pattern.compile("(^|[^0-9.])[0-9]{1,3}(,[0-9]{3})+([^0-9]|$)");
    private static final Pattern THOUSANDS_OTHER = Pattern.compile(
            "(^|[^0-9.,])[0-9]{1,3}(([.][0-9]{3}){2,}|([' \u00A0\u202F\u2009][0-9]{3})+)([^0-9]|$)");
    private static final Pattern SCIENTIFIC = Pattern.compile("[0-9.][eE][+-]?[0-9]");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String FORMULA_START = "=+-@\t\r";

    private CsvTable() {
    }

    // ---- export ----------------------------------------------------------------------------------------------

    /**
     * Writes the CSV file of a canonical rule set document: UTF-8 byte order mark, comma separator, CRLF line ends,
     * RFC 4180 quoting, amounts without thousands separators, ISO dates, {@code -} for any.
     *
     * @param canonicalJson the stored canonical JSON
     * @return the file text, starting with the byte order mark
     * @throws ValidationFailedException naming the rule (or column) that has no table form
     */
    public static String write(String canonicalJson) {
        JsonNode doc = EngineJson.JSON.readTree(canonicalJson);
        List<JsonNode> inputs = list(doc.get("inputs"));
        List<JsonNode> outputs = list(doc.get("outputs"));
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            slots.put(inputs.get(i).get("name").stringValue(), i);
            requireScalar(inputs.get(i), "input");
        }
        outputs.forEach(o -> requireScalar(o, "output"));

        StringBuilder out = new StringBuilder(BOM);
        List<String> header = new ArrayList<>();
        header.add("Rule");
        inputs.forEach(i -> header.add(i.get("name").stringValue()));
        outputs.forEach(o -> header.add(o.get("name").stringValue()));
        header.add("Description");
        line(out, header);

        for (JsonNode rule : list(doc.get("rules"))) {
            String id = rule.get("id").stringValue();
            JsonNode when = rule.get("when");
            if (when.has("any") || when.has("all")) {
                throw noForm(id, null, "uses a grouped condition (any or all); split it into rows, then export");
            }
            List<String> cells = new ArrayList<>();
            cells.add(id);
            for (JsonNode input : inputs) {
                String name = input.get("name").stringValue();
                cells.add(when.has(name) ? printCondition(id, name, when.get(name).stringValue(), slots) : "-");
            }
            JsonNode then = rule.get("then");
            for (JsonNode output : outputs) {
                String name = output.get("name").stringValue();
                JsonNode cell = then.get(name);
                if (cell == null) {
                    cells.add(output.has("default") ? "" : "null");
                } else {
                    cells.add(printOutput(id, name, cell));
                }
            }
            String description = rule.has("description") ? rule.get("description").stringValue() : "";
            cells.add(!description.isEmpty() && FORMULA_START.indexOf(description.charAt(0)) >= 0
                    ? "'" + description : description);
            line(out, cells);
        }
        return out.toString();
    }

    private static void requireScalar(JsonNode column, String kind) {
        if (column.get("type").stringValue().startsWith("list<")) {
            throw noForm(null, column.get("name").stringValue(), "the " + kind + " is a list; a list has no spreadsheet form");
        }
    }

    private static String printCondition(String ruleId, String input, String text, Map<String, Integer> slots) {
        UnaryTests tests;
        try {
            tests = V1Forms.fromCondition(input, text, n -> slots.getOrDefault(n, -1));
        } catch (Feel.UnsupportedException e) {
            throw noForm(ruleId, input, "has a condition with no spreadsheet form; write it as simple conditions, then export");
        }
        if (tests.any() || tests.tests().isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        for (Test test : tests.tests()) {
            parts.add(printTest(ruleId, input, test, tests.negated()));
        }
        String joined = String.join(", ", parts);
        return tests.negated() ? "!= " + joined : joined;
    }

    private static String printTest(String ruleId, String input, Test test, boolean negated) {
        boolean plain = test instanceof Test.IsNull || test instanceof Test.Compare c && c.op() == Op.EQ;
        if (negated && !plain) {
            throw noForm(ruleId, input, "negates a range or a comparison, which has no spreadsheet form");
        }
        return switch (test) {
            case Test.Compare(Op op, Value value) -> switch (op) {
                case EQ -> printValue(ruleId, input, value);
                case LT -> "< " + printValue(ruleId, input, value);
                case LE -> "<= " + printValue(ruleId, input, value);
                case GT -> "> " + printValue(ruleId, input, value);
                case GE -> ">= " + printValue(ruleId, input, value);
            };
            case Test.Interval(boolean lc, Value low, Value high, boolean hc) when lc && hc
                    && !(low instanceof Value.Str) ->
                    printValue(ruleId, input, low) + " to " + printValue(ruleId, input, high);
            case Test.Interval(boolean lc, Value low, Value high, boolean hc) ->
                    (lc ? "[" : "(") + printValue(ruleId, input, low) + ".." + printValue(ruleId, input, high)
                            + (hc ? "]" : ")");
            case Test.IsNull ignored -> "null";
        };
    }

    private static String printOutput(String ruleId, String output, JsonNode cell) {
        if (cell.isNull()) {
            return "null";
        }
        if (cell.isBoolean()) {
            return Boolean.toString(cell.booleanValue());
        }
        if (cell.isNumber()) {
            return cell.decimalValue().toPlainString();
        }
        try {
            return printValue(ruleId, output, V1Forms.literal(cell.stringValue()));
        } catch (Feel.UnsupportedException e) {
            throw noForm(ruleId, output, "is calculated, not a plain value, so it has no spreadsheet form");
        }
    }

    private static String printValue(String ruleId, String column, Value value) {
        return switch (value) {
            case Value.Str(String s) -> {
                for (int i = 0; i < s.length(); i++) {
                    if (V1Forms.needsUnicodeEscape(s.charAt(i))) {
                        throw noForm(ruleId, column, "holds a control or invisible character, which has no spreadsheet form");
                    }
                }
                yield needsQuotes(s) ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
            }
            case Value.Num(BigDecimal n) -> n.toPlainString();
            case Value.Bool(boolean b) -> Boolean.toString(b);
            case Value.Date(LocalDate d) -> d.toString();
            case Value.DateTime(Instant i) -> i.toString();
            case Value.Money(BigDecimal amount, String currency) -> amount.toPlainString() + " " + currency;
            case Value.Null() -> "null";
        };
    }

    private static boolean needsQuotes(String s) {
        if (s.isEmpty() || !s.equals(s.strip()) || s.equals("null") || s.contains(",") || s.contains("\"")
                || s.contains(" to ") || s.contains("..")) {
            return true;
        }
        return "<>=!([]-+@".indexOf(s.charAt(0)) >= 0;
    }

    private static ValidationFailedException noForm(String ruleId, String column, String what) {
        String where = ruleId != null ? "Rule " + ruleId + (column != null ? ", column " + column : "")
                : "Column " + column;
        return new ValidationFailedException(List.of(new Violation(ruleId, column,
                "Cannot export as CSV: " + where + " " + what, "CSV_NO_TABLE_FORM")));
    }

    private static void line(StringBuilder out, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(csvField(cells.get(i)));
        }
        out.append("\r\n");
    }

    private static String csvField(String cell) {
        boolean quote = cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\r') >= 0
                || cell.indexOf('\n') >= 0 || !cell.isEmpty() && (cell.charAt(0) == ' ' || cell.charAt(cell.length() - 1) == ' ');
        return quote ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    private static List<JsonNode> list(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    // ---- import ----------------------------------------------------------------------------------------------

    /**
     * Reads a CSV file against the inputs, outputs, hit policy and masks of a base version and returns the rule set
     * document, in the stored JSON form, that the engine then checks exactly as it checks an imported document.
     *
     * @param baseCanonicalJson the canonical JSON of the base version
     * @param csv the file bytes
     * @return the document JSON text
     * @throws ValidationFailedException listing each problem with its rule and column, never quoting a cell
     */
    public static String read(String baseCanonicalJson, byte[] csv) {
        JsonNode base = EngineJson.JSON.readTree(baseCanonicalJson);
        List<JsonNode> inputs = list(base.get("inputs"));
        List<JsonNode> outputs = list(base.get("outputs"));
        Problems problems = new Problems();

        String text = decode(csv, problems);
        List<List<String>> rows = text == null ? List.of() : parse(text, problems);
        if (problems.isEmpty() && rows.stream().allMatch(List::isEmpty)) {
            problems.add(null, null, "The file is empty", "CSV_EMPTY");
        }
        List<String> columns = new ArrayList<>();
        columns.add("Rule");
        inputs.forEach(i -> columns.add(i.get("name").stringValue()));
        outputs.forEach(o -> columns.add(o.get("name").stringValue()));
        int firstRow = 0;
        while (problems.isEmpty() && firstRow < rows.size() - 1 && rows.get(firstRow).isEmpty()) {
            firstRow++;
        }
        List<String> head = problems.isEmpty() ? rows.get(firstRow) : List.of();
        int[] slot = problems.isEmpty() ? header(head, columns, problems) : new int[0];
        if (!problems.isEmpty()) {
            throw problems.failure();
        }

        int descriptionSlot = slot[columns.size()];
        Map<String, JsonNode> baseRules = new HashMap<>();
        base.get("rules").forEach(r -> baseRules.put(r.get("id").stringValue(), r));
        List<String> ids = ruleIds(rows, firstRow, slot[0], problems);
        ArrayNode rules = EngineJson.NODES.arrayNode();
        for (int r = firstRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            String id = ids.get(r);
            if (id == null) {
                continue;
            }
            if (row.size() != head.size()) {
                problems.add(id, null, "Row " + (r + 1) + " has " + row.size() + " cells; the header has "
                        + head.size(), "CSV_ROW_SHAPE");
                continue;
            }
            ObjectNode rule = EngineJson.NODES.objectNode();
            rule.put("id", id);
            String description = descriptionSlot < 0 ? null : unguard(row.get(descriptionSlot).strip());
            JsonNode previous = baseRules.get(id);
            if (descriptionSlot < 0 && previous != null && previous.has("description")) {
                description = previous.get("description").stringValue();
            }
            if (description != null && !description.isEmpty()) {
                rule.put("description", description);
            }
            if (previous != null && previous.has("explain")) {
                rule.set("explain", previous.get("explain"));
            }
            ObjectNode when = rule.putObject("when");
            ObjectNode then = rule.putObject("then");
            for (int i = 0; i < inputs.size(); i++) {
                JsonNode input = inputs.get(i);
                String name = input.get("name").stringValue();
                try {
                    UnaryTests tests = parseInput(row.get(slot[1 + i]), kind(input));
                    if (!tests.any()) {
                        when.put(name, V1Forms.condition(name, tests));
                    }
                } catch (CellProblem e) {
                    problems.add(id, name, e.getMessage(), e.code);
                }
            }
            for (int o = 0; o < outputs.size(); o++) {
                JsonNode output = outputs.get(o);
                String name = output.get("name").stringValue();
                try {
                    putOutput(then, name, row.get(slot[1 + inputs.size() + o]), kind(output), output.has("default"));
                } catch (CellProblem e) {
                    problems.add(id, name, e.getMessage(), e.code);
                }
            }
            rules.add(rule);
        }
        if (!problems.isEmpty()) {
            throw problems.failure();
        }
        ObjectNode doc = ((ObjectNode) base).deepCopy();
        doc.set("rules", rules);
        return EngineJson.JSON.writeValueAsString(doc);
    }

    private static ValueType.Kind kind(JsonNode column) {
        return ValueType.parse(column.get("type").stringValue()).kind();
    }

    /** Maps each expected column to its position in the file; the last entry is the Description position or -1. */
    private static int[] header(List<String> header, List<String> expected, Problems problems) {
        Map<String, Integer> at = new HashMap<>();
        Set<String> seen = new HashSet<>();
        int description = -1;
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).strip();
            if (!seen.add(name)) {
                problems.add(null, name, "The column is listed twice", "CSV_COLUMN_DUPLICATE");
            } else if (name.equals("Description")) {
                description = i;
            } else if (expected.contains(name)) {
                at.put(name, i);
            } else {
                problems.add(null, name, "The base version has no such column", "CSV_COLUMN_UNKNOWN");
            }
        }
        for (String name : expected) {
            if (!at.containsKey(name)) {
                problems.add(null, name, "The file has no such column", "CSV_COLUMN_MISSING");
            }
        }
        int[] slot = new int[expected.size() + 1];
        if (problems.isEmpty()) {
            int previous = -1;
            for (int i = 0; i < expected.size(); i++) {
                slot[i] = at.get(expected.get(i));
                if (slot[i] < previous) {
                    problems.add(null, expected.get(i), "The column is out of order; keep the order of the base version",
                            "CSV_COLUMN_ORDER");
                    break;
                }
                previous = slot[i];
            }
            if (description >= 0 && description < previous) {
                problems.add(null, "Description", "The Description column goes last", "CSV_COLUMN_ORDER");
            }
        }
        slot[expected.size()] = description;
        return slot;
    }

    /**
     * The id of each row by row index, a blank one replaced by the next free id; null for the header, a blank row and
     * a row that was refused.
     */
    private static List<String> ruleIds(List<List<String>> rows, int firstRow, int slot, Problems problems) {
        Set<String> used = new HashSet<>();
        for (int r = firstRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (slot < row.size() && !row.get(slot).isBlank()) {
                used.add(row.get(slot).strip());
            }
        }
        List<String> ids = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (r <= firstRow || row.isEmpty()) {
                ids.add(null);
                continue;
            }
            String id = slot < row.size() ? row.get(slot).strip() : "";
            if (id.isEmpty()) {
                int n = r - firstRow;
                while (used.contains("R" + n) || taken.contains("R" + n)) {
                    n++;
                }
                id = "R" + n;
            } else if (!RULE_ID.matcher(id).matches()) {
                problems.add(null, "Rule", "Row " + (r + 1) + " has a rule id that is not valid: start with a letter, then"
                        + " letters, digits, '-' or '_', at most 64 characters", "CSV_RULE_ID_INVALID");
                ids.add(null);
                continue;
            }
            if (!taken.add(id)) {
                problems.add(id, "Rule", "The rule id is used by an earlier row", "CSV_RULE_DUPLICATE");
                ids.add(null);
                continue;
            }
            ids.add(id);
        }
        return ids;
    }

    private static String unguard(String description) {
        return description.length() > 1 && description.charAt(0) == '\'' && FORMULA_START.indexOf(description.charAt(1)) >= 0
                ? description.substring(1) : description;
    }

    private static String decode(byte[] csv, Problems problems) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(csv)).toString();
            return text.startsWith(BOM) ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            problems.add(null, null, "The file is not UTF-8; save it as CSV UTF-8", "CSV_ENCODING");
            return null;
        }
    }

    /** RFC 4180 with the separator, comma or semicolon, read from the header line. */
    private static List<List<String>> parse(String text, Problems problems) {
        char separator = separator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty() && !wasQuoted) {
                quoted = true;
                wasQuoted = true;
            } else if (c == separator) {
                row.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                wasQuoted = false;
                addRow(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
            i++;
        }
        if (quoted) {
            problems.add(null, null, "A quoted cell is not closed", "CSV_FILE_INVALID");
            return List.of();
        }
        if (!field.isEmpty() || !row.isEmpty()) {
            row.add(field.toString());
            addRow(rows, row);
        }
        return rows;
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        // a blank row keeps its place so that a row number is the number Excel shows
        rows.add(row.stream().allMatch(String::isBlank) ? List.of() : row);
    }

    private static char separator(String text) {
        boolean quoted = false;
        int commas = 0;
        int semicolons = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted && c == ',') {
                commas++;
            } else if (!quoted && c == ';') {
                semicolons++;
            }
        }
        return semicolons > 0 && commas == 0 ? ';' : ',';
    }

    // ---- cells -----------------------------------------------------------------------------------------------

    /** A cell that cannot be read, with the code a program acts on; the message never quotes the cell. */
    private static final class CellProblem extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        CellProblem(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    private static UnaryTests parseInput(String raw, ValueType.Kind kind) {
        String cell = raw.strip();
        if (cell.isEmpty() || cell.equals("-")) {
            return UnaryTests.ANY;
        }
        checkDamage(cell, kind);
        boolean negated = false;
        if (cell.startsWith("!=")) {
            negated = true;
            cell = cell.substring(2).strip();
        }
        List<Test> tests = new ArrayList<>();
        for (String item : split(cell)) {
            if (tests.size() >= Feel.MAX_TESTS) {
                throw new CellProblem("CSV_CELL_INVALID", "The cell has too many conditions");
            }
            tests.add(parseTest(item.strip(), kind, negated));
        }
        return new UnaryTests(false, negated, tests);
    }

    private static Test parseTest(String item, ValueType.Kind kind, boolean negated) {
        if (item.isEmpty()) {
            throw new CellProblem("CSV_CELL_INVALID", "The cell has an empty condition; remove the extra comma");
        }
        Op op = null;
        String rest = item;
        for (String symbol : List.of("<=", ">=", "<", ">", "=")) {
            if (item.startsWith(symbol)) {
                op = switch (symbol) {
                    case "<=" -> Op.LE;
                    case ">=" -> Op.GE;
                    case "<" -> Op.LT;
                    case ">" -> Op.GT;
                    default -> Op.EQ;
                };
                rest = item.substring(symbol.length()).strip();
                break;
            }
        }
        if (op != null) {
            if (negated && op != Op.EQ) {
                throw new CellProblem("CSV_CELL_INVALID", "After != write plain values only, for example != GOLD, SILVER");
            }
            return new Test.Compare(op, value(rest, kind));
        }
        if (item.startsWith("[") || item.startsWith("(")) {
            if (negated) {
                throw new CellProblem("CSV_CELL_INVALID", "After != write plain values only, for example != GOLD, SILVER");
            }
            return interval(item, kind);
        }
        if (kind != ValueType.Kind.STRING && !item.startsWith("\"")) {
            int to = item.toLowerCase(Locale.ROOT).indexOf(" to ");
            if (to > 0) {
                if (negated) {
                    throw new CellProblem("CSV_CELL_INVALID", "After != write plain values only, for example != GOLD, SILVER");
                }
                return new Test.Interval(true, value(item.substring(0, to).strip(), kind),
                        value(item.substring(to + 4).strip(), kind), true);
            }
        }
        if (item.equals("null")) {
            return new Test.IsNull();
        }
        return new Test.Compare(Op.EQ, value(item, kind));
    }

    private static Test interval(String item, ValueType.Kind kind) {
        char open = item.charAt(0);
        char close = item.charAt(item.length() - 1);
        int dots = item.indexOf("..");
        if (dots < 0 || (close != ']' && close != ')')) {
            throw new CellProblem("CSV_CELL_INVALID", "A range is written [low..high], (low..high), or low to high");
        }
        return new Test.Interval(open == '[', value(item.substring(1, dots).strip(), kind),
                value(item.substring(dots + 2, item.length() - 1).strip(), kind), close == ']');
    }

    private static void putOutput(ObjectNode then, String name, String raw, ValueType.Kind kind, boolean hasDefault) {
        String cell = raw.strip();
        if (cell.isEmpty()) {
            if (hasDefault) {
                return; // the output's default applies, as when the rule left the output out
            }
            throw new CellProblem("CSV_CELL_EMPTY", "The output cell is empty; write null for no value");
        }
        checkDamage(cell, kind);
        Value value = cell.equals("null") ? new Value.Null() : value(cell, kind);
        switch (value) {
            case Value.Null() -> then.putNull(name);
            case Value.Bool(boolean b) -> then.put(name, b);
            case Value.Num(BigDecimal n) when CanonicalCells.isJsonInteger(n) -> then.put(name, n.setScale(0));
            case Value.Num(BigDecimal n) -> then.put(name, n.toPlainString());
            default -> then.put(name, V1Forms.v1(value));
        }
    }

    /** Spreadsheet damage that must never be read as another value, found before the cell is split. */
    private static void checkDamage(String cell, ValueType.Kind kind) {
        boolean numeric = kind == ValueType.Kind.INTEGER || kind == ValueType.Kind.DECIMAL
                || kind == ValueType.Kind.MONEY;
        if (numeric && SCIENTIFIC.matcher(cell).find()) {
            throw new CellProblem("CSV_CELL_SCIENTIFIC", "Scientific notation such as 5E+07 is not read; write the"
                    + " whole number, for example 50000000");
        }
        if (numeric && (THOUSANDS_COMMA.matcher(cell).find() || THOUSANDS_OTHER.matcher(cell).find())) {
            throw new CellProblem("CSV_CELL_SEPARATORS", "Write the amount without separators, for example 50000000 VND");
        }
        for (int i = 0; i < cell.length(); i++) {
            if (V1Forms.needsUnicodeEscape(cell.charAt(i))) {
                throw new CellProblem("CSV_CELL_INVALID", "The cell holds a control or invisible character");
            }
        }
    }

    private static Value value(String text, ValueType.Kind kind) {
        if (text.isEmpty()) {
            throw new CellProblem("CSV_CELL_INVALID", "The cell has a condition with no value");
        }
        if (text.length() > Feel.MAX_LENGTH) {
            throw new CellProblem("CSV_CELL_INVALID", "The cell is longer than " + Feel.MAX_LENGTH + " characters");
        }
        return switch (kind) {
            case STRING -> new Value.Str(string(text));
            case INTEGER, DECIMAL -> new Value.Num(number(text));
            case BOOLEAN -> switch (text.toLowerCase(Locale.ROOT)) {
                case "true" -> new Value.Bool(true);
                case "false" -> new Value.Bool(false);
                default -> throw new CellProblem("CSV_CELL_INVALID", "Write true or false");
            };
            case DATE -> {
                try {
                    yield new Value.Date(LocalDate.parse(text));
                } catch (DateTimeParseException e) {
                    throw dateProblem();
                }
            }
            case DATETIME -> {
                try {
                    yield new Value.DateTime(Instant.parse(text));
                } catch (DateTimeParseException e) {
                    throw new CellProblem("CSV_CELL_DATE", "Write the date and time as an ISO instant, for example"
                            + " 2026-11-20T09:30:00Z");
                }
            }
            case MONEY -> money(text);
        };
    }

    private static CellProblem dateProblem() {
        return new CellProblem("CSV_CELL_DATE", "Write the date as year-month-day, for example 2026-11-20");
    }

    private static String string(String text) {
        if (text.startsWith("\"")) {
            if (text.length() < 2 || !text.endsWith("\"")) {
                throw new CellProblem("CSV_CELL_INVALID", "A quoted text is not closed");
            }
            return text.substring(1, text.length() - 1).replace("\"\"", "\"");
        }
        return text;
    }

    private static BigDecimal number(String text) {
        if (!NUMBER.matcher(text).matches()) {
            throw new CellProblem("CSV_CELL_NOT_A_NUMBER", "The cell holds text in a number column; write digits only,"
                    + " for example 18 or 2.5");
        }
        return new BigDecimal(text);
    }

    private static Value money(String text) {
        int space = text.lastIndexOf(' ');
        if (space < 0) {
            if (NUMBER.matcher(text).matches()) {
                throw new CellProblem("CSV_CELL_NO_CURRENCY", "The amount has no currency; write the currency after it,"
                        + " for example 50000000 VND");
            }
            throw new CellProblem("CSV_CELL_NOT_A_NUMBER", "The cell is not an amount; write for example 50000000 VND");
        }
        String amount = text.substring(0, space).strip();
        String currency = text.substring(space + 1).strip();
        if (!NUMBER.matcher(amount).matches()) {
            throw new CellProblem("CSV_CELL_NOT_A_NUMBER", "The cell is not an amount; write for example 50000000 VND");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            throw new CellProblem("CSV_CELL_NO_CURRENCY", "The currency is three capital letters, for example VND");
        }
        return new Value.Money(new BigDecimal(amount), currency);
    }

    /** Splits a cell at its commas that are outside quotes. */
    private static List<String> split(String cell) {
        List<String> items = new ArrayList<>();
        StringBuilder item = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < cell.length(); i++) {
            char c = cell.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                item.append(c);
            } else if (c == ',' && !quoted) {
                items.add(item.toString());
                item.setLength(0);
            } else {
                item.append(c);
            }
        }
        items.add(item.toString());
        return items;
    }

    /** The problems found, cut at {@link #MAX_PROBLEMS}. */
    private static final class Problems {
        private final List<Violation> list = new ArrayList<>();
        private boolean truncated;

        void add(String ruleId, String column, String message, String code) {
            if (list.size() >= MAX_PROBLEMS) {
                truncated = true;
            } else {
                list.add(new Violation(ruleId, column, message, code));
            }
        }

        boolean isEmpty() {
            return list.isEmpty();
        }

        ValidationFailedException failure() {
            return new ValidationFailedException(list, truncated);
        }
    }
}
