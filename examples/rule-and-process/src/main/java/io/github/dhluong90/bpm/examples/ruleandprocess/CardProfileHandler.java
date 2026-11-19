package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code cardProfiles.load}: reads the customer's card tier and monthly transaction count from the host's own
 * table, in the engine's transaction. An unknown customer is a business error, so the start rolls back.
 */
@Component
class CardProfileHandler implements ServiceTaskHandler {

    private final JdbcClient jdbc;

    CardProfileHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "cardProfiles.load";
    }

    @Override
    public HandlerResult handle(ServiceCall call) {
        Object customerId = call.inputs().get("customerId");
        return jdbc.sql("SELECT tier, tx_count FROM card_customer WHERE tenant_id = ? AND customer_id = ?")
                .params(call.tenantId(), String.valueOf(customerId))
                .query((rs, row) -> HandlerResult.completed(Map.of("tier", rs.getString("tier"),
                        "txCount", rs.getLong("tx_count"))))
                .optional()
                .orElseGet(() -> new HandlerResult.BusinessError("CUSTOMER_NOT_FOUND",
                        "No customer " + customerId + " in this tenant"));
    }
}
