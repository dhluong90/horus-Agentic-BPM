package io.github.dhluong90.bpm.rules.api;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plain-language readings of rule rows, built by the engine so every host shows the same text (PD-027, RLE-091 to
 * RLE-093). Each reading is written in every language of the message catalogue and in two styles: a full sentence and
 * a short form. A masked input is named as masked and its test is never written. The records below hold the readings
 * of one rule set version, of the difference between two versions and of a simulation result.
 */
public final class RuleReadings {

    private RuleReadings() {
    }

    /**
     * One reading of one row in one language.
     *
     * @param full the full sentence, such as {@code Rule R1 applies when Balance is at least 50,000,000 VND.}
     * @param shortForm the short form, such as {@code Balance >= 50,000,000 VND}
     * @param source {@code TEMPLATE} when the full sentence is the author's own for the row (PD-123), otherwise
     *     {@code GENERATED}
     */
    public record Reading(String full, String shortForm, String source) implements Serializable {

        public Reading {
            Objects.requireNonNull(full, "full must not be null");
            Objects.requireNonNull(shortForm, "shortForm must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    /**
     * One row of a version.
     *
     * @param ruleId the rule id
     * @param position the 1-based place of the row in the table
     * @param readings the reading by language code
     */
    public record Row(String ruleId, int position, Map<String, Reading> readings) implements Serializable {

        public Row {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            readings = Map.copyOf(readings);
        }
    }

    /**
     * The readings of every row of a version.
     *
     * @param ruleSet the rule set name
     * @param version the version read
     * @param languages the language codes the readings are written in, sorted
     * @param rows every row in table order
     */
    public record RuleSetReadings(String ruleSet, int version, List<String> languages, List<Row> rows)
            implements Serializable {

        public RuleSetReadings {
            Objects.requireNonNull(ruleSet, "ruleSet must not be null");
            languages = List.copyOf(languages);
            rows = List.copyOf(rows);
        }
    }

    /**
     * One changed row of a diff, read in both versions.
     *
     * @param ruleId the rule id
     * @param change how the row differs
     * @param position the 1-based place in {@code version}, or null when removed
     * @param basePosition the 1-based place in {@code base}, or null when added
     * @param readingChanged whether any reading differs, so a screen strikes the old one only when it changed
     * @param previous the readings in {@code base} by language, empty when added
     * @param current the readings in {@code version} by language, empty when removed
     */
    public record ChangedRow(String ruleId, VersionDiff.Change change, Integer position, Integer basePosition,
            boolean readingChanged, Map<String, Reading> previous, Map<String, Reading> current)
            implements Serializable {

        public ChangedRow {
            Objects.requireNonNull(ruleId, "ruleId must not be null");
            Objects.requireNonNull(change, "change must not be null");
            previous = Map.copyOf(previous);
            current = Map.copyOf(current);
        }
    }

    /**
     * The readings of the rows a diff lists.
     *
     * @param ruleSet the rule set name
     * @param version the version compared
     * @param base the version it is compared against
     * @param languages the language codes the readings are written in, sorted
     * @param rows the changed rows, in the order of the diff
     * @param unchangedCount rows present in both with the same cells and not moved, not listed
     */
    public record ReadingsDiff(String ruleSet, int version, int base, List<String> languages, List<ChangedRow> rows,
            int unchangedCount) implements Serializable {

        public ReadingsDiff {
            Objects.requireNonNull(ruleSet, "ruleSet must not be null");
            languages = List.copyOf(languages);
            rows = List.copyOf(rows);
        }
    }

    /**
     * One side of a simulated sample, read.
     *
     * @param kind {@code MATCHED}, {@code NO_MATCH} or {@code FAILED}
     * @param ruleIds the matched rule ids
     * @param readings the sentence of the outcome by language
     */
    public record OutcomeReading(String kind, List<String> ruleIds, Map<String, Reading> readings)
            implements Serializable {

        public OutcomeReading {
            Objects.requireNonNull(kind, "kind must not be null");
            ruleIds = List.copyOf(ruleIds);
            readings = Map.copyOf(readings);
        }
    }

    /**
     * One simulated sample, read. The sample's input values are never part of it.
     *
     * @param index the position in the request, from 0
     * @param changed as in {@link SimulationResult.Sample#changed()}
     * @param simulated the simulated version's outcome
     * @param baseline the effective version's outcome, or null when none is effective
     */
    public record SampleReading(int index, boolean changed, OutcomeReading simulated, OutcomeReading baseline)
            implements Serializable {

        public SampleReading {
            Objects.requireNonNull(simulated, "simulated must not be null");
        }
    }

    /**
     * The readings of a simulation result.
     *
     * @param ruleSet the rule set name
     * @param version the simulated version
     * @param baselineVersion the version effective at the clock, or null
     * @param languages the language codes the readings are written in, sorted
     * @param samples one reading per sample, in the order sent
     */
    public record SimulationReadings(String ruleSet, int version, Integer baselineVersion, List<String> languages,
            List<SampleReading> samples) implements Serializable {

        public SimulationReadings {
            Objects.requireNonNull(ruleSet, "ruleSet must not be null");
            languages = List.copyOf(languages);
            samples = List.copyOf(samples);
        }
    }
}
