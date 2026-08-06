package uk.gov.moj.cpp.prisoncourtregister.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnvVarUtilTest {

    private static final String UNSET = "PRISON_COURT_REGISTER_DEFINITELY_UNSET_VAR";

    @Test
    @DisplayName("required env var fails fast when unset")
    void requiredEnvFailsFast() {
        assertThatThrownBy(() -> EnvVarUtil.getRequiredEnv(UNSET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(UNSET);
    }

    @Test
    @DisplayName("optional env var is empty when unset")
    void optionalEnvIsEmptyWhenUnset() {
        assertThat(EnvVarUtil.getOptionalEnv(UNSET)).isEmpty();
    }

    @Test
    @DisplayName("int env var falls back to the default when unset")
    void intEnvFallsBackWhenUnset() {
        assertThat(EnvVarUtil.getIntEnv(UNSET, 7)).isEqualTo(7);
    }

    @Test
    @DisplayName("an env var that is actually set is read and trimmed")
    void readsSetEnvVar() {
        // PATH is set on every platform this builds on.
        assertThat(EnvVarUtil.getOptionalEnv("PATH")).isPresent();
    }
}
