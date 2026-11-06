package com.aegisx.integrity;

import com.aegisx.auth.entity.Role;
import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integrity.domain.WorkspaceMemberRole;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.service.WorkspaceMembershipService;
import com.aegisx.integrity.support.IntegrityTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@DisplayName("Workspace membership authorization")
class WorkspaceMembershipAuthorizationTest extends IntegrityTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceMembershipService membershipService;

    @Test
    @DisplayName("members see only their workspace and cross-tenant routes are denied before side effects")
    void membersAreScopedToTheirWorkspaces() throws Exception {
        User firstUser = createAdmin("first-" + UUID.randomUUID() + "@aegis-x.test");
        User secondUser = createAdmin("second-" + UUID.randomUUID() + "@aegis-x.test");
        Workspace firstWorkspace = newWorkspace();
        Workspace secondWorkspace = newWorkspace();
        membershipService.addMember(firstUser.getId(), firstWorkspace.getId(), WorkspaceMemberRole.OWNER);
        membershipService.addMember(secondUser.getId(), secondWorkspace.getId(), WorkspaceMemberRole.OWNER);

        String firstBase = "/api/integrity/workspaces/" + firstWorkspace.getId();
        String secondBase = "/api/integrity/workspaces/" + secondWorkspace.getId();

        mockMvc.perform(get("/api/integrity/workspaces").with(user(firstUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(firstWorkspace.getId().toString()));
        mockMvc.perform(get("/api/integrity/workspaces").with(user(secondUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(secondWorkspace.getId().toString()));

        mockMvc.perform(get(firstBase + "/dashboard").with(user(firstUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(post(firstBase + "/orders")
                        .with(user(firstUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":12000,\"currency\":\"INR\",\"description\":\"owned\"}"))
                .andExpect(status().isOk());

        long workspacesBefore = workspaceRepository.count();
        long ordersBefore = orderRepository.count();
        long runsBefore = runRepository.count();
        long exceptionsBefore = exceptionCaseRepository.count();
        long documentsBefore = evidenceDocumentRepository.count();
        long auditBefore = auditEventRepository.count();

        assertEveryWorkspaceRouteDenied(firstBase, secondUser.getEmail());
        assertEveryWorkspaceRouteDenied(secondBase, firstUser.getEmail());

        assertThat(workspaceRepository.count()).isEqualTo(workspacesBefore);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
        assertThat(runRepository.count()).isEqualTo(runsBefore);
        assertThat(exceptionCaseRepository.count()).isEqualTo(exceptionsBefore);
        assertThat(evidenceDocumentRepository.count()).isEqualTo(documentsBefore);
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore);
    }

    private User createAdmin(String email) {
        return userRepository.save(User.builder()
                .fullName("Workspace Membership Test User")
                .email(email)
                .passwordHash("not-used-by-mock-authentication")
                .role(Role.ADMIN)
                .build());
    }

    private void assertEveryWorkspaceRouteDenied(String base, String callerEmail) throws Exception {
        UUID arbitraryId = UUID.randomUUID();
        String evidence = base + "/evidence";
        String exceptions = base + "/exceptions";
        String reconciliation = base + "/reconciliation";
        String recommendations = base + "/recommendations";
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf", new byte[]{1, 2, 3});

        List<MockHttpServletRequestBuilder> requests = new ArrayList<>(List.of(
                get(base),
                get(base + "/configuration"),
                post(base + "/connectivity-check"),
                patch(base + "/staleness-threshold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stalenessThresholdSeconds\":1800}"),
                get(base + "/dashboard"),
                post(base + "/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1000,\"currency\":\"INR\"}"),
                get(base + "/orders"),
                get(base + "/orders/" + arbitraryId),
                get(base + "/payments"),
                get(base + "/refunds"),
                get(base + "/transfers"),
                get(evidence + "/documents").param("orderId", arbitraryId.toString()),
                get(evidence + "/documents/" + arbitraryId + "/content"),
                post(evidence + "/documents/" + arbitraryId + "/extract"),
                get(evidence + "/documents/" + arbitraryId + "/extractions"),
                post(evidence + "/documents/" + arbitraryId + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"invoiceNumber\",\"value\":\"x\"}"),
                get(evidence + "/requirements").param("orderId", arbitraryId.toString()),
                get(evidence + "/readiness"),
                post(evidence + "/assess"),
                get(evidence + "/summary").param("orderId", arbitraryId.toString()),
                get(evidence + "/model"),
                get(evidence + "/health"),
                get(exceptions),
                get(exceptions + "/all"),
                get(exceptions + "/" + arbitraryId),
                post(exceptions + "/" + arbitraryId + "/open"),
                post(exceptions + "/" + arbitraryId + "/notes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"),
                post(exceptions + "/" + arbitraryId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"summary\":\"x\"}"),
                post(exceptions + "/" + arbitraryId + "/dismiss")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"),
                post(reconciliation + "/runs").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(reconciliation + "/methods"),
                get(reconciliation + "/runs"),
                get(reconciliation + "/runs/" + arbitraryId + "/findings"),
                get(reconciliation + "/findings"),
                get(recommendations),
                get(recommendations + "/by-exception/" + arbitraryId),
                post(recommendations + "/" + arbitraryId + "/approve"),
                post(recommendations + "/" + arbitraryId + "/reject"),
                post(recommendations + "/" + arbitraryId + "/execute"),
                get(base + "/audit"),
                get(base + "/webhooks")));
        requests.add(multipart(evidence + "/documents")
                .file(file)
                .param("orderId", arbitraryId.toString())
                .param("documentType", "COMMERCIAL_INVOICE"));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.with(SecurityMockMvcRequestPostProcessors.user(callerEmail).roles("ADMIN")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Workspace not found"));
        }
    }
}
