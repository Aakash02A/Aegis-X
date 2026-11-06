package com.aegisx.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Content-Security-Policy against reintroducing third-party origins.
 *
 * <p>The policy previously carried {@code https://cdn.jsdelivr.net} for the Font Awesome stylesheet
 * and the webfonts. Both are self-hosted now, but the allowance stayed behind, which meant an
 * injected script or stylesheet could be loaded from that CDN and the policy would permit it. The
 * frontend test enforces that no page or stylesheet references a CDN; this test enforces the other
 * half of the same invariant.</p>
 */
class ContentSecurityPolicyTest {

    private static List<String> directive(String policy, String name) {
        return Arrays.stream(policy.split(";"))
                .map(String::trim)
                .filter(part -> part.startsWith(name + " "))
                .findFirst()
                .map(part -> Arrays.stream(part.substring(name.length()).trim().split("\\s+"))
                        .filter(token -> !token.isBlank())
                        .collect(Collectors.toList()))
                .orElseThrow(() -> new AssertionError("Missing CSP directive: " + name));
    }

    @Test
    @DisplayName("Only the hosted Razorpay Checkout script is allowed outside the application origin")
    void resourceDirectivesAllowOnlyRazorpayCheckout() {
        String policy = SecurityConfig.CONTENT_SECURITY_POLICY;

        List<String> scriptHosts = directive(policy, "script-src").stream()
                .filter(token -> token.contains("://") || token.startsWith("//"))
                .toList();
        assertThat(scriptHosts).containsExactly("https://checkout.razorpay.com");

        for (String name : List.of("style-src", "font-src")) {
            List<String> hosts = directive(policy, name).stream()
                    .filter(token -> token.contains("://") || token.startsWith("//"))
                    .toList();
            assertThat(hosts)
                    .as("%s must not allow an external origin", name)
                    .isEmpty();
        }

        assertThat(directive(policy, "default-src")).contains("'self'");
        assertThat(directive(policy, "script-src")).contains("'self'");
        assertThat(directive(policy, "font-src")).containsExactly("'self'");
        assertThat(directive(policy, "frame-ancestors")).containsExactly("'none'");
    }

    @Test
    @DisplayName("The retired icon and font CDN is gone from the policy")
    void noRetiredCdnOrigins() {
        assertThat(SecurityConfig.CONTENT_SECURITY_POLICY)
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("fonts.googleapis.com")
                .doesNotContain("fonts.gstatic.com");
    }
}
