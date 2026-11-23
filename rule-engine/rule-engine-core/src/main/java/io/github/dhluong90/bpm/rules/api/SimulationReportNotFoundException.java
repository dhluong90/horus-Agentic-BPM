package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 404 SIMULATION_REPORT_NOT_FOUND}: the version has no report with this id in the caller's tenant. The answer is
 * the same for a report of another tenant, another rule set or another version (RLE-200).
 */
public final class SimulationReportNotFoundException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version
     * @param reportId the report id asked for
     */
    public SimulationReportNotFoundException(String ruleSet, int version, String reportId) {
        super("SIMULATION_REPORT_NOT_FOUND", "Version " + version + " of rule set " + ruleSet
                + " has no simulation report " + reportId);
    }
}
