package io.github.dhluong90.bpm.examples.guardedagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoRoleCatalogueTest {

    @Test
    void PRE_180_the_demo_catalogue_lists_the_demo_roles_of_the_dev_tenant_only() {
        DemoRoleCatalogue catalogue = new DemoRoleCatalogue("dev");

        assertThat(catalogue.exists("dev", "UNDERWRITER")).isTrue();
        assertThat(catalogue.exists("dev", "RISK_HEAD")).as("a role nobody holds").isFalse();
        assertThat(catalogue.exists("bank-b", "UNDERWRITER")).as("another tenant").isFalse();
        assertThat(new DemoRoleCatalogue("").exists("dev", "UNDERWRITER")).as("no dev tenant configured").isFalse();
    }
}
