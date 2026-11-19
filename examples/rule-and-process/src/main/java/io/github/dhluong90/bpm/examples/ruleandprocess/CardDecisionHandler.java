package io.github.dhluong90.bpm.examples.ruleandprocess;

import com.horus.bpm.rules.api.Money;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code cardApplications.decide}: records the outcome and the priced fee on the host's application row, in the
 * engine's transaction, so the engine state and the host state commit together.
 */
@Component
class CardDecisionHandler implements ServiceTaskHandler {

    private final JdbcClient jdbc;
    private final Clock clock;

    CardDecisionHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.clock = Clock.systemUTC();
    }

    @Override
    public String name() {
        return "cardApplications.decide";
    }

    @Override
    public HandlerResult handle(ServiceCall call) {
        String outcome = String.valueOf(call.inputs().get("outcome"));
        if (!(call.inputs().get("fee") instanceof Money fee)) {
            return new HandlerResult.Failed("FEE_MISSING", "The fee input must be money", false);
        }
        int updated = jdbc.sql("UPDATE card_application SET status = ?, fee_currency = ?, fee_minor = ?, decided_at = ? "
                        + "WHERE tenant_id = ? AND application_id = ?")
                .params(outcome, fee.currency(), fee.minorUnits(), clock.instant().atOffset(ZoneOffset.UTC),
                        call.tenantId(), String.valueOf(call.inputs().get("applicationId")))
                .update();
        if (updated != 1) {
            return new HandlerResult.BusinessError("APPLICATION_NOT_FOUND", "No application "
                    + call.inputs().get("applicationId"));
        }
        return HandlerResult.completed(Map.of("outcome", outcome));
    }
}
