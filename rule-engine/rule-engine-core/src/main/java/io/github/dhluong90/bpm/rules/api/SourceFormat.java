package io.github.dhluong90.bpm.rules.api;

/**
 * Format of an imported source document, stored byte for byte beside the canonical version (TR-STD-01). New values
 * may be added; do not switch on it exhaustively.
 */
public enum SourceFormat {
    JSON,
    YAML,
    DMN_XML,
    /** A spreadsheet file (PD-107): exported, and imported against a base version; stored as {@link #JSON}. */
    CSV
}
