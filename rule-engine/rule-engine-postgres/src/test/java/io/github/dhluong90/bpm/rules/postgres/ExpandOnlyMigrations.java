package io.github.dhluong90.bpm.rules.postgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds what a migration does that a replica of the previous release cannot live with (RLE-197): the schema a release
 * migrates is read and written by replicas of the release before it for as long as the upgrade rolls. A migration
 * therefore only expands: new tables, new nullable or defaulted columns, new indexes, wider constraints. A statement
 * that removes, renames, retypes or tightens something the previous release uses is a finding, and needs an entry
 * naming the version, the rule and the reason in the test that calls this class.
 *
 * <p>Reads the migration files as text, strips comments and looks at statements; it is a lint, not a parser, so a
 * dynamic statement inside a {@code DO} block is read as the text it is.
 */
final class ExpandOnlyMigrations {

    /** One thing in a migration that is not an expansion. */
    record Finding(String file, int version, String rule, String statement) {
        @Override
        public String toString() {
            return file + " [" + rule + "]: " + statement;
        }
    }

    private static final Pattern FILE = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern STATEMENT_SPLIT = Pattern.compile(";");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;
    /** Rule name to the statement text that breaks it. */
    private static final List<Rule> RULES = List.of(
            new Rule("DROP_TABLE", Pattern.compile("\\bDROP\\s+TABLE\\b", FLAGS)),
            new Rule("DROP_COLUMN", Pattern.compile("\\bDROP\\s+COLUMN\\b", FLAGS)),
            new Rule("RENAME_TABLE_OR_COLUMN", Pattern.compile("^\\s*ALTER\\s+TABLE\\b.*\\bRENAME\\b", FLAGS)),
            new Rule("RETYPE_COLUMN", Pattern.compile("\\bALTER\\s+COLUMN\\s+\\S+\\s+(SET\\s+DATA\\s+)?TYPE\\b", FLAGS)),
            new Rule("SET_NOT_NULL", Pattern.compile("\\bALTER\\s+COLUMN\\s+\\S+\\s+SET\\s+NOT\\s+NULL\\b", FLAGS)),
            new Rule("DROP_CONSTRAINT", Pattern.compile("\\bDROP\\s+CONSTRAINT\\b", FLAGS)),
            new Rule("TRUNCATE", Pattern.compile("^\\s*TRUNCATE\\b", FLAGS)),
            new Rule("DELETE_ROWS", Pattern.compile("\\bDELETE\\s+FROM\\b", FLAGS)));
    private static final Pattern ADD_COLUMN = Pattern.compile("\\bADD\\s+COLUMN\\b(.*?)(?=,\\s*(ADD|DROP|ALTER)\\b|$)",
            FLAGS | Pattern.DOTALL);

    private record Rule(String name, Pattern pattern) {
    }

    private ExpandOnlyMigrations() {
    }

    /**
     * @param folder the folder holding {@code V<n>__<name>.sql}
     * @param afterVersion only migrations numbered above this are read: the head of the previous release
     * @return every finding, in version order
     * @throws IOException when the folder cannot be read
     */
    static List<Finding> scan(Path folder, int afterVersion) throws IOException {
        List<Finding> findings = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
        for (Path file : files) {
            Matcher name = FILE.matcher(file.getFileName().toString());
            name.matches();
            int version = Integer.parseInt(name.group(1));
            if (version > afterVersion) {
                findings.addAll(scan(file.getFileName().toString(), version, Files.readString(file,
                        StandardCharsets.UTF_8)));
            }
        }
        return findings.stream().sorted(java.util.Comparator.comparingInt(Finding::version)
                .thenComparing(Finding::rule)).toList();
    }

    /**
     * @param file the file name
     * @param version its version
     * @param sql the script
     * @return what in it is not an expansion
     */
    static List<Finding> scan(String file, int version, String sql) {
        String text = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ").replaceAll("\\s+", " ");
        List<Finding> findings = new ArrayList<>();
        for (String statement : STATEMENT_SPLIT.split(text)) {
            String s = statement.strip();
            if (s.isEmpty()) {
                continue;
            }
            for (Rule rule : RULES) {
                if (rule.pattern().matcher(s).find()) {
                    findings.add(new Finding(file, version, rule.name(), abbreviate(s)));
                }
            }
            Matcher add = ADD_COLUMN.matcher(s);
            while (add.find()) {
                String clause = add.group(1).toUpperCase(Locale.ROOT);
                if (clause.contains("NOT NULL") && !clause.contains("DEFAULT") && !clause.contains("GENERATED")) {
                    findings.add(new Finding(file, version, "ADD_NOT_NULL_COLUMN_WITHOUT_DEFAULT", abbreviate(s)));
                }
            }
        }
        return findings;
    }

    private static String abbreviate(String statement) {
        return statement.length() > 120 ? statement.substring(0, 117) + "..." : statement;
    }
}
