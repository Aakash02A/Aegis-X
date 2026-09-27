package com.aegisx.web.controller;

import com.aegisx.auth.service.UserService;
import com.aegisx.transaction.service.TransactionService;
import com.aegisx.settlement.service.SettlementService;
import com.aegisx.policy.service.PolicyViolationService;
import com.aegisx.review.service.ComplianceReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Main web controller for server-rendered Thymeleaf views.
 * Implements SRS Section 10 - User Interface Requirements.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class WebController {

    private final UserService userService;
    private final TransactionService transactionService;
    private final SettlementService settlementService;
    private final PolicyViolationService policyViolationService;
    private final ComplianceReviewService complianceReviewService;

    /**
     * Home page - redirects to appropriate dashboard based on user role.
     */
    @GetMapping("/")
    public String home(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return "redirect:/login";
        }
        
        // Redirect based on user role
        if (authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_COMPLIANCE_OFFICER"))) {
            return "redirect:/compliance/dashboard";
        } else if (authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT"))) {
            return "redirect:/merchant/dashboard";
        } else if (authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SETTLEMENT_OPERATOR"))) {
            return "redirect:/operations/dashboard";
        }
        
        return "redirect:/dashboard";
    }

    /**
     * Login page.
     */
    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
                       @RequestParam(value = "logout", required = false) String logout,
                       Model model) {
        if (error != null) {
            model.addAttribute("error", "Invalid username or password");
        }
        if (logout != null) {
            model.addAttribute("message", "You have been logged out successfully");
        }
        return "auth/login";
    }

    /**
     * Registration page.
     */
    @GetMapping("/register")
    public String register() {
        return "auth/register";
    }

    /**
     * Main dashboard for general users.
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, Authentication authentication) {
        try {
            // Add basic dashboard data
            model.addAttribute("userName", authentication.getName());
            model.addAttribute("userRoles", authentication.getAuthorities());
            model.addAttribute("currentTime", LocalDateTime.now());
            
            // Add summary statistics
            addDashboardStats(model);
            
            return "dashboard/main";
        } catch (Exception e) {
            log.error("Error loading dashboard", e);
            model.addAttribute("error", "Failed to load dashboard data");
            return "error/500";
        }
    }

    /**
     * Merchant dashboard.
     */
    @GetMapping("/merchant/dashboard")
    public String merchantDashboard(Model model, Authentication authentication) {
        try {
            model.addAttribute("userName", authentication.getName());
            model.addAttribute("userType", "Merchant");
            
            // Add merchant-specific data
            addMerchantDashboardData(model, authentication.getName());
            
            return "dashboard/merchant";
        } catch (Exception e) {
            log.error("Error loading merchant dashboard", e);
            model.addAttribute("error", "Failed to load merchant dashboard");
            return "error/500";
        }
    }

    /**
     * Compliance officer dashboard.
     */
    @GetMapping("/compliance/dashboard")
    public String complianceDashboard(Model model, Authentication authentication) {
        try {
            model.addAttribute("userName", authentication.getName());
            model.addAttribute("userType", "Compliance Officer");
            
            // Add compliance-specific data
            addComplianceDashboardData(model);
            
            return "dashboard/compliance";
        } catch (Exception e) {
            log.error("Error loading compliance dashboard", e);
            model.addAttribute("error", "Failed to load compliance dashboard");
            return "error/500";
        }
    }

    /**
     * Operations dashboard.
     */
    @GetMapping("/operations/dashboard")
    public String operationsDashboard(Model model, Authentication authentication) {
        try {
            model.addAttribute("userName", authentication.getName());
            model.addAttribute("userType", "Settlement Operator");
            
            // Add operations-specific data
            addOperationsDashboardData(model);
            
            return "dashboard/operations";
        } catch (Exception e) {
            log.error("Error loading operations dashboard", e);
            model.addAttribute("error", "Failed to load operations dashboard");
            return "error/500";
        }
    }

    /**
     * Transaction list page.
     */
    @GetMapping("/transactions")
    public String transactionList(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size,
                                 @RequestParam(required = false) String status,
                                 Model model,
                                 Authentication authentication) {
        try {
            // Add transaction list data
            model.addAttribute("transactions", "transactions-data"); // Placeholder
            model.addAttribute("currentPage", page);
            model.addAttribute("pageSize", size);
            model.addAttribute("statusFilter", status);
            
            return "transaction/list";
        } catch (Exception e) {
            log.error("Error loading transactions", e);
            model.addAttribute("error", "Failed to load transactions");
            return "error/500";
        }
    }

    /**
     * Transaction detail page.
     */
    @GetMapping("/transactions/{id}")
    public String transactionDetail(@PathVariable UUID id, Model model) {
        try {
            // Add transaction detail data
            model.addAttribute("transactionId", id);
            
            return "transaction/detail";
        } catch (Exception e) {
            log.error("Error loading transaction detail: {}", id, e);
            model.addAttribute("error", "Failed to load transaction details");
            return "error/404";
        }
    }

    /**
     * Compliance review queue.
     */
    @GetMapping("/compliance/reviews")
    public String complianceReviews(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size,
                                   Model model) {
        try {
            // Add review queue data
            model.addAttribute("reviews", "reviews-data"); // Placeholder
            model.addAttribute("currentPage", page);
            model.addAttribute("pageSize", size);
            
            return "compliance/reviews";
        } catch (Exception e) {
            log.error("Error loading compliance reviews", e);
            model.addAttribute("error", "Failed to load compliance reviews");
            return "error/500";
        }
    }

    /**
     * Settlement list page.
     */
    @GetMapping("/settlements")
    public String settlementList(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(required = false) String status,
                                Model model) {
        try {
            // Add settlement list data
            model.addAttribute("settlements", "settlements-data"); // Placeholder
            model.addAttribute("currentPage", page);
            model.addAttribute("pageSize", size);
            model.addAttribute("statusFilter", status);
            
            return "settlement/list";
        } catch (Exception e) {
            log.error("Error loading settlements", e);
            model.addAttribute("error", "Failed to load settlements");
            return "error/500";
        }
    }

    /**
     * Settlement detail page.
     */
    @GetMapping("/settlements/{id}")
    public String settlementDetail(@PathVariable UUID id, Model model) {
        try {
            // Add settlement detail data
            model.addAttribute("settlementId", id);
            
            return "settlement/detail";
        } catch (Exception e) {
            log.error("Error loading settlement detail: {}", id, e);
            model.addAttribute("error", "Failed to load settlement details");
            return "error/404";
        }
    }

    /**
     * Policy violations page.
     */
    @GetMapping("/violations")
    public String violations(@RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size,
                            @RequestParam(required = false) String severity,
                            Model model) {
        try {
            // Add violations data
            model.addAttribute("violations", "violations-data"); // Placeholder
            model.addAttribute("currentPage", page);
            model.addAttribute("pageSize", size);
            model.addAttribute("severityFilter", severity);
            
            return "policy/violations";
        } catch (Exception e) {
            log.error("Error loading violations", e);
            model.addAttribute("error", "Failed to load violations");
            return "error/500";
        }
    }

    /**
     * Reports page.
     */
    @GetMapping("/reports")
    public String reports(Model model) {
        try {
            // Add reports data
            model.addAttribute("reportTypes", "report-types"); // Placeholder
            
            return "reports/main";
        } catch (Exception e) {
            log.error("Error loading reports", e);
            model.addAttribute("error", "Failed to load reports");
            return "error/500";
        }
    }

    /**
     * User profile page.
     */
    @GetMapping("/profile")
    public String profile(Model model, Authentication authentication) {
        try {
            // Add user profile data
            model.addAttribute("userName", authentication.getName());
            model.addAttribute("userRoles", authentication.getAuthorities());
            
            return "user/profile";
        } catch (Exception e) {
            log.error("Error loading user profile", e);
            model.addAttribute("error", "Failed to load user profile");
            return "error/500";
        }
    }

    // Helper methods for adding dashboard data

    private void addDashboardStats(Model model) {
        try {
            // Add basic statistics - placeholder values for now
            model.addAttribute("totalTransactions", 0);
            model.addAttribute("pendingReviews", 0);
            model.addAttribute("activeSettlements", 0);
            model.addAttribute("criticalViolations", 0);
        } catch (Exception e) {
            log.warn("Failed to load dashboard statistics", e);
        }
    }

    private void addMerchantDashboardData(Model model, String merchantId) {
        try {
            // Add merchant-specific data - placeholder for now
            model.addAttribute("myTransactions", 0);
            model.addAttribute("pendingApprovals", 0);
            model.addAttribute("recentSettlements", 0);
            model.addAttribute("documentsNeeded", 0);
        } catch (Exception e) {
            log.warn("Failed to load merchant dashboard data", e);
        }
    }

    private void addComplianceDashboardData(Model model) {
        try {
            // Add compliance-specific data - placeholder for now
            model.addAttribute("reviewQueue", 0);
            model.addAttribute("highRiskTransactions", 0);
            model.addAttribute("overdueReviews", 0);
            model.addAttribute("todayApprovals", 0);
        } catch (Exception e) {
            log.warn("Failed to load compliance dashboard data", e);
        }
    }

    private void addOperationsDashboardData(Model model) {
        try {
            // Add operations-specific data - placeholder for now
            model.addAttribute("processingSettlements", 0);
            model.addAttribute("failedSettlements", 0);
            model.addAttribute("overdueSettlements", 0);
            model.addAttribute("reconciliationPending", 0);
        } catch (Exception e) {
            log.warn("Failed to load operations dashboard data", e);
        }
    }
}
