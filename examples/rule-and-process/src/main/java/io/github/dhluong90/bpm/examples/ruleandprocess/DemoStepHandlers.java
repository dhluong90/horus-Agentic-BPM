package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.HandlerResult;
import io.github.dhluong90.bpm.process.spi.ServiceCall;
import io.github.dhluong90.bpm.process.spi.ServiceTaskHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The simple service steps the demo and conformance models call: reserving and releasing funds, posting a debit and
 * its credit-back, notifying a customer, scoring, and the card and dispute steps. Each one is a local handler with no
 * network call and no state of its own: it logs the step and answers a small result the process can read. A real host
 * replaces them with its own handlers under the same names, or with remote steps.
 */
@Configuration(proxyBeanMethods = false)
class DemoStepHandlers {

    private static final Log LOG = LogFactory.getLog(DemoStepHandlers.class);

    /** Above this amount in minor units the demo refund step answers the business error REFUND_LIMIT_EXCEEDED. */
    static final long REFUND_LIMIT_MINOR = 5_000_000L;

    /** A handler under one name, with its answer built from the call. */
    record Step(String name, boolean compensable, Function<ServiceCall, HandlerResult> body)
            implements ServiceTaskHandler {

        @Override
        public HandlerResult handle(ServiceCall call) {
            HandlerResult result = body.apply(call);
            LOG.info("demo.step name=" + name + " tenant=" + call.tenantId() + " instance=" + call.instanceId()
                    + " node=" + call.nodeId() + " result=" + result.getClass().getSimpleName());
            return result;
        }
    }

    private static Step step(String name, boolean compensable, Function<ServiceCall, Map<String, ?>> output) {
        return new Step(name, compensable, call -> HandlerResult.completed(output.apply(call)));
    }

    private static Map<String, Object> of(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static String suffix(ServiceCall call) {
        String id = call.instanceId();
        return id.substring(Math.max(0, id.length() - 6));
    }

    @Bean
    ServiceTaskHandler reserveFunds() {
        return step("reserve-funds", true, call -> of("reservationId", "RSV-" + suffix(call), "status", "RESERVED"));
    }

    @Bean
    ServiceTaskHandler releaseFunds() {
        return step("release-funds", false, call -> of("status", "RELEASED"));
    }

    @Bean
    ServiceTaskHandler postDebit() {
        return step("post-debit", true, call -> of("entryId", "DR-" + suffix(call), "status", "POSTED"));
    }

    @Bean
    ServiceTaskHandler postCreditBack() {
        return step("post-credit-back", false, call -> of("status", "REVERSED"));
    }

    @Bean
    ServiceTaskHandler notifyCustomer() {
        return step("notify-customer", false, call -> of("notified", true, "channel", "SMS"));
    }

    @Bean
    ServiceTaskHandler notifyTreasury() {
        return step("notify-treasury", false, call -> of("notified", true));
    }

    @Bean
    ServiceTaskHandler reverseDisbursement() {
        return step("reverse-disbursement", false, call -> of("reversed", true));
    }

    @Bean
    ServiceTaskHandler paymentsRelease() {
        return step("payments.release", false, call -> of("paymentId", "PAY-" + suffix(call), "status", "RELEASED"));
    }

    /** A fixed score in the demo; the data input of the step is read by the host's data store adapter. */
    @Bean
    ServiceTaskHandler creditScore() {
        return step("credit.score", false, call -> of("score", 712, "band", "B"));
    }

    @Bean
    ServiceTaskHandler cardsOpenAccount() {
        return step("cards.open-account", false, call -> of("accountNumber", "ACC-" + suffix(call)));
    }

    @Bean
    ServiceTaskHandler cardsEmboss() {
        return step("cards.emboss", false, call -> of("embossed", true));
    }

    @Bean
    ServiceTaskHandler disputesRemind() {
        return step("disputes.remind", false, call -> of("reminded", true));
    }

    @Bean
    ServiceTaskHandler disputesNotify() {
        return step("disputes.notify", false, call -> of("notified", true));
    }

    /** The refund step: an amount above {@link #REFUND_LIMIT_MINOR} is a business error the dispute process routes. */
    @Bean
    ServiceTaskHandler disputesRefund() {
        return new Step("disputes.refund", true, call -> {
            Object amount = call.inputs().get("amountMinor");
            if (amount instanceof Number number && number.longValue() > REFUND_LIMIT_MINOR) {
                return new HandlerResult.BusinessError("REFUND_LIMIT_EXCEEDED", "The refund is above the officer's limit");
            }
            return HandlerResult.completed(of("refunded", true));
        });
    }

    /**
     * The credit step of {@code loan-payout}: a start variable {@code failCredit = true} makes it answer the business
     * error CREDIT_REJECTED, so a presenter chooses the happy path or the undo.
     */
    @Bean
    ServiceTaskHandler payoutCredit() {
        return new Step("payout.credit", false, call -> {
            if (Boolean.TRUE.equals(call.inputs().get("failCredit"))) {
                return new HandlerResult.BusinessError("CREDIT_REJECTED", "The credit to the customer was rejected");
            }
            return HandlerResult.completed(of("credited", true));
        });
    }
}
