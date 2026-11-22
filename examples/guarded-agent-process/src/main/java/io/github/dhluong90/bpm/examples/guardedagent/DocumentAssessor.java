package io.github.dhluong90.bpm.examples.guardedagent;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The document assessor of the demo: a deterministic stand-in for a model, so the journey runs without an API key
 * and gives the same answers every time. It is naive on purpose. It approves every complete file, with a risk score
 * that grows with the amount, and refers a file with missing documents. The guardrail, not the agent, stops the
 * risky approval. Swap it for one of the reference model adapters of {@code examples/model-adapters} to ask a real
 * model; the process and the guardrail stay the same.
 */
@Component
class DocumentAssessor implements AgentAdapter {

    /** The adapter name the agent step names with {@code horus:agent}. */
    static final String NAME = "documents.assess";
    /** From this amount, in VND, the assessor scores a file as high risk (and still approves it). */
    static final long LARGE_AMOUNT = 1_000_000_000L;
    /** The documents a complete file holds: ID card, salary slips, employment contract. */
    static final int COMPLETE_FILE = 3;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public AdapterResult execute(AgentInvocation invocation) {
        long amount = ((Number) invocation.inputs().get("requestedAmount")).longValue();
        int documents = ((Number) invocation.inputs().get("documentCount")).intValue();
        String application = String.valueOf(invocation.inputs().get("applicationId"));
        if (documents < COMPLETE_FILE) {
            return AdapterResult.completed(Map.of("riskScore", 55, "recommendation", "REFER",
                    "summary", documents + " of " + COMPLETE_FILE + " documents in file " + application));
        }
        return AdapterResult.completed(Map.of("riskScore", amount >= LARGE_AMOUNT ? 85 : 20,
                "recommendation", "APPROVE", "summary", "Complete file " + application));
    }
}
