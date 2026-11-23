package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.MessageAdapter;
import java.time.OffsetDateTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where the messages a process sends go (send task, message throw and message end events): the host's own
 * {@code event_outbox}, written in the step's transaction, so a message exists if and only if the step committed. The
 * row's event id is the message id, so a step that runs again leaves one row. A relay (not part of this sample)
 * delivers the rows after commit.
 */
@Configuration(proxyBeanMethods = false)
class DemoMessageAdapter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    MessageAdapter outboxMessageAdapter(JdbcClient jdbc) {
        return message -> jdbc.sql("INSERT INTO event_outbox (tenant_id, event_id, event_type, business_key, payload, "
                        + "occurred_at) VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (tenant_id, event_id) DO NOTHING")
                .params(message.tenantId(), message.messageId(), "message." + message.name(), message.correlationKey(),
                        JSON.writeValueAsString(message.payload()), OffsetDateTime.now())
                .update();
    }
}
