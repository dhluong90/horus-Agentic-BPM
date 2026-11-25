package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service steps and the screening agent of the {@code loan-origination} demo model. Local stand-ins with no network
 * call: the CIC report is a small table by customer, the agent is deterministic, and the ledger answers as a start
 * variable says (like {@code failCredit} of {@code loan-payout}). A real host registers its own under the same names.
 * Demo switches, read from the start variables: {@code simulatePepHit}, {@code simulateFraud},
 * {@code simulateCollateralFraud}, {@code simulateLedgerFailure}, {@code simulateDebtGroup}. Only the boolean
 * {@code true} switches a failure on.
 */
@Configuration(proxyBeanMethods = false)
class LoanOriginationSteps {

    private static Map<String, Object> of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static boolean on(ServiceCall call, String name) {
        return Boolean.TRUE.equals(call.inputs().get(name));
    }

    private static ServiceTaskHandler step(String name, Function<ServiceCall, HandlerResult> body) {
        return new DemoStepHandlers.Step(name, false, body);
    }

    private static String suffix(ServiceCall call) {
        String id = call.instanceId();
        return id.substring(Math.max(0, id.length() - 6));
    }

    /** The CIC report: customer CUS-100390 scores 724 with a 44 percent DSR, anyone else 712 and 38; group 1 unless set. */
    @Bean
    ServiceTaskHandler loanValidate() {
        return step("loan.validate", call -> {
            boolean second = "CUS-100390".equals(call.inputs().get("customerId"));
            Object group = call.inputs().get("simulateDebtGroup");
            return HandlerResult.completed(of("worstDebtGroup12m", group instanceof Number n ? n.intValue() : 1,
                    "scorecardScore", second ? 724 : 712, "dsrPercent", second ? 44 : 38));
        });
    }

    @Bean
    ServiceTaskHandler offerPrepare() {
        return step("offer.prepare", call -> HandlerResult.completed(of("offerId", "OFR-" + suffix(call))));
    }

    @Bean
    ServiceTaskHandler collateralRegister() {
        return step("collateral.register", call -> {
            if (on(call, "simulateCollateralFraud")) {
                return new HandlerResult.BusinessError("COLLATERAL_FRAUD_FLAG", "The asset is double-pledged");
            }
            return HandlerResult.completed(Boolean.TRUE.equals(call.inputs().get("secured"))
                    ? of("registryRef", "REG-" + suffix(call), "status", "REGISTERED") : of("status", "NOT_REQUIRED"));
        });
    }

    @Bean
    ServiceTaskHandler limitReserve() {
        return step("limit.reserve", call -> HandlerResult.completed(of("reservationId", "LMT-" + suffix(call))));
    }

    @Bean
    ServiceTaskHandler limitRelease() {
        return step("limit.release", call -> HandlerResult.completed(of("released", true)));
    }

    @Bean
    ServiceTaskHandler loanBook() {
        return step("loan.book", call -> HandlerResult.completed(of("journalId", "JE-" + suffix(call))));
    }

    @Bean
    ServiceTaskHandler loanReverse() {
        return step("loan.reverse", call -> HandlerResult.completed(of("reversed", true)));
    }

    /** The pay-out: {@code simulateLedgerFailure = true} makes it answer the business error LEDGER_REJECTED. */
    @Bean
    ServiceTaskHandler loanPayout() {
        return step("loan.payout", call -> on(call, "simulateLedgerFailure")
                ? new HandlerResult.BusinessError("LEDGER_REJECTED", "The ledger rejected the pay-out")
                : HandlerResult.completed(of("journalId", "JE-PAY-" + suffix(call))));
    }

    /** The screening agent: clear, unless {@code simulatePepHit} or {@code simulateFraud} is true. It never decides. */
    @Bean
    AgentAdapter kycScreeningAgent() {
        return new AgentAdapter() {
            @Override
            public String name() {
                return "kyc.screen";
            }

            @Override
            public AdapterResult execute(io.github.dhluong90.bpm.process.spi.AgentInvocation invocation) {
                boolean pep = Boolean.TRUE.equals(invocation.inputs().get("simulatePepHit"));
                boolean fraud = Boolean.TRUE.equals(invocation.inputs().get("simulateFraud"));
                return AdapterResult.completed(of("kycStatus", fraud ? "FAIL" : pep ? "REVIEW" : "PASS",
                        "idConfidence", new java.math.BigDecimal("0.97"), "nameMatches", true, "tamperingSuspected", false, "sanctionsHit", false,
                        "pepHit", pep, "adverseMedia", false, "fraudFlag", fraud,
                        "summary", "Documents of application " + invocation.inputs().get("applicationId") + " read"));
            }
        };
    }
}
