package com.zachary.BI.integration.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips integration tests on a developer machine where Docker is not running,
 * but never in CI: there a skipped suite would look green while testing nothing.
 * GitHub Actions (and most CI systems) set the environment variable CI=true.
 */
public class RequiresDockerCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (IntegrationTestContainers.isDockerAvailable()) {
            return ConditionEvaluationResult.enabled("Docker is available");
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return ConditionEvaluationResult.enabled(
                    "Docker is unavailable, but CI must not silently skip integration tests");
        }
        return ConditionEvaluationResult.disabled("Docker is not running; integration tests skipped locally");
    }
}
