package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 409 SIMULATION_REPORT_REQUIRED}: the host lists the rule set in
 * {@code horus.bpm.rules.lifecycle.require-simulation-report} and the publication request carries no
 * {@code simulationReportId} (PD-115, RLE-201).
 */
public final class SimulationReportRequiredException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version a publication was requested for
     */
    public SimulationReportRequiredException(String ruleSet, int version) {
        super("SIMULATION_REPORT_REQUIRED", "Rule set " + ruleSet + " needs a simulation report before publication of"
                + " version " + version + " is requested: run POST .../versions/" + version
                + "/simulation-reports and send its id as simulationReportId");
    }
}
