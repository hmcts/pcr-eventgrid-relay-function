package uk.gov.moj.cpp.prisoncourtregister;

import java.util.logging.Logger;

import com.microsoft.azure.functions.ExecutionContext;

/**
 * Minimal {@link ExecutionContext} stub. The function logs via SLF4J and does not read the
 * context, so nothing here needs to be meaningful.
 */
final class TestExecutionContext implements ExecutionContext {

    private static final Logger LOGGER = Logger.getLogger(TestExecutionContext.class.getName());

    @Override
    public Logger getLogger() {
        return LOGGER;
    }

    @Override
    public String getInvocationId() {
        return "test-invocation-id";
    }

    @Override
    public String getFunctionName() {
        return "PrisonCourtRegisterHearingResulted";
    }
}
