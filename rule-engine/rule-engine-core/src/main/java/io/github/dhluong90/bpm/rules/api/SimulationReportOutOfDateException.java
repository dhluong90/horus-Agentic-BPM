package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 409 SIMULATION_REPORT_OUT_OF_DATE}: the report was run on content the version no longer has, so it cannot be
 * attached to a publication request (PD-115, RLE-201). Run a new report on the draft as it is now.
 */
public final class SimulationReportOutOfDateException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version
     * @param reportId the report id
     */
    public SimulationReportOutOfDateException(String ruleSet, int version, String reportId) {
        super("SIMULATION_REPORT_OUT_OF_DATE", "Simulation report " + reportId + " was run before version " + version
                + " of rule set " + ruleSet + " changed; run a new report on the draft as it is now");
    }
}
