package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DemoRoleCatalogueTest {

    @Test
    void PRE_180_the_demo_catalogue_lists_the_demo_roles_of_the_dev_tenant_only() {
        DemoRoleCatalogue catalogue = new DemoRoleCatalogue("dev");

        assertThat(catalogue.exists("dev", "BRANCH_SUPERVISOR")).isTrue();
        assertThat(catalogue.exists("dev", "RISK_HEAD")).as("a role nobody holds").isFalse();
        assertThat(catalogue.exists("bank-b", "BRANCH_SUPERVISOR")).as("another tenant").isFalse();
        assertThat(new DemoRoleCatalogue("").exists("dev", "BRANCH_SUPERVISOR")).as("no dev tenant configured").isFalse();
    }
}
