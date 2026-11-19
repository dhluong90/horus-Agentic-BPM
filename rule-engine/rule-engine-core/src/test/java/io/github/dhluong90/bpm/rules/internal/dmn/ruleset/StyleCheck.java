package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

/** Rule set style-check of RLE-039: the same table in author style (A) and in normal form (B). */
public final class StyleCheck {

    private StyleCheck() {
    }

    public static final String A = """
            schemaVersion: 1
            ruleSet: style-check
            hitPolicy: first
            inputs:
              - { name: x, type: integer }
              - { name: y, type: integer }
              - { name: country, type: string }
              - { name: at, type: datetime }
              - { name: amount, type: money }
            outputs:
              - { name: o, type: string }
              - { name: rate, type: decimal }
            rules:
              - { id: R1, when: { x: "x >= 3" }, then: { o: "'R1'", rate: "0.10" } }
              - { id: R2, when: { x: "x between 1 and 5" }, then: { o: "'R2'" } }
              - { id: R3, when: { x: "x is not null" }, then: { o: "'R3'" } }
              - { id: R4, when: { country: "country != 'VN'" }, then: { o: "'R4'" } }
              - { id: R5, when: { country: "in ['VN','SG']" }, then: { o: "'R5'" } }
              - { id: R6, when: { at: "== datetime('2026-12-01T00:00:00+07:00')" }, then: { o: "'R6'" } }
              - { id: R7, when: { amount: "== 12.5 USD" }, then: { o: "'R7'" } }
            """;

    public static final String B = A
            .replace("\"x >= 3\"", "\">= 3\"")
            .replace("\"x between 1 and 5\"", "\"between 1 and 5\"")
            .replace("\"x is not null\"", "\"is not null\"")
            .replace("\"country != 'VN'\"", "\"!= 'VN'\"")
            .replace("\"in ['VN','SG']\"", "\"in ['VN', 'SG']\"")
            .replace("== datetime('2026-12-01T00:00:00+07:00')", "== datetime('2026-11-30T17:00:00Z')")
            .replace("\"== 12.5 USD\"", "\"== 12.50 USD\"");
}
