package com.aegisx.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps application startup independent of an advisory integration.
 *
 * <p>Spring AI's OpenAI auto-configuration refuses to build its client when
 * {@code spring.ai.openai.api-key} is blank, and an empty {@code OPENAI_API_KEY} environment
 * variable counts as blank even though {@code application.yml} supplies a default — a placeholder
 * default only applies when the variable is <em>unset</em>. The result was that every checkout
 * that followed the documented quick start (copy {@code .env.example}, run {@code docker compose up})
 * crashed the whole backend at startup over a component that is explicitly advisory.</p>
 *
 * <p>This post-processor therefore normalises a missing or empty key to a sentinel that is not a
 * credential. The client can be built, the application starts, and {@code AIAnalysisService}
 * refuses to send a request while the sentinel is in place, reporting a clear
 * {@code AIAnalysisException} instead of contacting the provider. When a real key is configured
 * nothing changes.</p>
 */
public class BlankOpenAiKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Not a credential: it only lets the OpenAI client bean be constructed. */
    public static final String NOT_CONFIGURED_KEY = "aegisx-openai-disabled";

    private static final String OPENAI_KEY_PROPERTY = "spring.ai.openai.api-key";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(OPENAI_KEY_PROPERTY);
        if (configured != null && !configured.isBlank() && !NOT_CONFIGURED_KEY.equals(configured.trim())) {
            return;
        }

        Map<String, Object> defaults = new HashMap<>();
        defaults.put(OPENAI_KEY_PROPERTY, NOT_CONFIGURED_KEY);
        environment.getPropertySources()
            .addFirst(new MapPropertySource("aegisxBlankOpenAiKey", defaults));
    }

    @Override
    public int getOrder() {
        // After ConfigData (application.yml) so the resolved value is what gets inspected.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
