package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/** The directory this host gives the process engine for reassign and delegate (PD-110). */
class UserStoreAssigneeDirectoryTest {

    private final InMemoryUserDetailsManager users = new InMemoryUserDetailsManager(
            User.withUsername("hoa").password("{noop}x").roles("TELLER", "BRANCH_SUPERVISOR").build());

    @Test
    void a_user_holds_the_roles_of_the_user_store_in_the_dev_tenant_only() {
        UserStoreAssigneeDirectory directory = new UserStoreAssigneeDirectory(users, "bank-a");

        assertThat(directory.holdsRole("bank-a", "hoa", "BRANCH_SUPERVISOR")).isTrue();
        assertThat(directory.holdsRole("bank-a", "hoa", "UNDERWRITER")).isFalse();
        assertThat(directory.holdsRole("bank-b", "hoa", "BRANCH_SUPERVISOR")).isFalse();
        assertThat(directory.holdsRole("bank-a", "nobody", "TELLER")).isFalse();
        assertThat(directory.inGroup("bank-a", "hoa", "branch-1")).isFalse();
    }

    @Test
    void without_a_dev_tenant_nobody_holds_anything() {
        UserStoreAssigneeDirectory directory = new UserStoreAssigneeDirectory(users, "");

        assertThat(directory.holdsRole("bank-a", "hoa", "TELLER")).isFalse();
    }
}
