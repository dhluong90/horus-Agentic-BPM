package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Binds the decision log gauge {@code horus.rle.partition.default.rows} (Prometheus
 * {@code horus_rle_partition_default_rows}, DESIGN section 4.8) to the host's meter registry. The value is read
 * when the registry asks, so it reflects the default partition at that moment; it should be 0, and above 0 a row
 * fell outside every monthly partition. A failed read reports {@code NaN}. No tag carries a tenant or a rule set.
 */
final class RuleEngineMetrics implements MeterBinder {

    static final String DEFAULT_ROWS = "horus.rle.partition.default.rows";

    private static final Log LOG = LogFactory.getLog(RuleEngineMetrics.class);

    private final DecisionLogPartitions partitions;

    RuleEngineMetrics(DecisionLogPartitions partitions) {
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(DEFAULT_ROWS, this, RuleEngineMetrics::defaultRows)
                .description("Rows of rle_decision outside every monthly partition; should be 0")
                // A gauge holds its state weakly by default: once nothing else references this binder, a garbage
                // collection turns the gauge into NaN. The registry keeps it, whoever created it.
                .strongReference(true)
                .register(registry);
    }

    private double defaultRows() {
        try {
            return partitions.defaultRows();
        } catch (RuntimeException e) {
            LOG.debug("Rule Engine could not count the default partition", e);
            return Double.NaN;
        }
    }
}
