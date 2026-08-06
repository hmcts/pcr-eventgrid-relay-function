package uk.gov.moj.cpp.prisoncourtregister.util;

import java.util.Optional;

/**
 * Reads Function App settings from the environment.
 */
public final class EnvVarUtil {

    private EnvVarUtil() {
    }

    /**
     * @throws IllegalStateException if the variable is unset or blank — fail fast at construction
     *                               rather than per-event.
     */
    public static String getRequiredEnv(final String name) {
        return getOptionalEnv(name).orElseThrow(() ->
                new IllegalStateException("Required environment variable '" + name + "' is not set"));
    }

    public static Optional<String> getOptionalEnv(final String name) {
        final String value = System.getenv(name);
        return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value.trim());
    }

    /**
     * Reads an int setting, falling back to {@code defaultValue} when unset, blank, or not a number.
     */
    public static int getIntEnv(final String name, final int defaultValue) {
        return getOptionalEnv(name)
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}
