package com.aegisx.integrity.service;

import com.aegisx.auth.entity.User;
import com.aegisx.auth.repository.UserRepository;
import com.aegisx.integrity.domain.ProviderMode;
import com.aegisx.integrity.domain.WorkspaceMemberRole;
import com.aegisx.integrity.entity.Workspace;
import com.aegisx.integrity.entity.WorkspaceMembership;
import com.aegisx.integrity.exception.IntegrityNotFoundException;
import com.aegisx.integrity.repository.WorkspaceMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkspaceMembershipService {

    private final UserRepository userRepository;
    private final WorkspaceMembershipRepository membershipRepository;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public List<Workspace> listFor(Authentication authentication) {
        User user = requireUser(authentication);
        return membershipRepository.findWorkspacesByUserId(user.getId());
    }

    @Transactional(readOnly = true)
    public void requireMembership(UUID workspaceId, Authentication authentication) {
        User user = requireUser(authentication);
        if (!membershipRepository.existsByUser_IdAndWorkspace_Id(user.getId(), workspaceId)) {
            throw new IntegrityNotFoundException("Workspace not found");
        }
    }

    @Transactional
    public Workspace createForUser(String name, ProviderMode mode, Authentication authentication) {
        User user = requireUser(authentication);
        Workspace workspace = workspaceService.create(name, mode);
        addMember(user, workspace, WorkspaceMemberRole.OWNER);
        return workspace;
    }

    @Transactional
    public Workspace defaultFor(Authentication authentication) {
        User user = requireUser(authentication);
        List<Workspace> existing = membershipRepository.findWorkspacesByUserId(user.getId());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        Workspace workspace = workspaceService.create(
                WorkspaceService.DEFAULT_WORKSPACE_NAME + " - " + user.getId(),
                ProviderMode.RAZORPAY_TEST);
        addMember(user, workspace, WorkspaceMemberRole.OWNER);
        return workspace;
    }

    @Transactional
    public WorkspaceMembership addMember(UUID userId, UUID workspaceId, WorkspaceMemberRole role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IntegrityNotFoundException("User not found"));
        Workspace workspace = workspaceService.get(workspaceId);
        return addMember(user, workspace, role);
    }

    private WorkspaceMembership addMember(User user, Workspace workspace, WorkspaceMemberRole role) {
        return membershipRepository.findByUser_IdAndWorkspace_Id(user.getId(), workspace.getId())
                .orElseGet(() -> membershipRepository.save(WorkspaceMembership.builder()
                        .user(user)
                        .workspace(workspace)
                        .role(role)
                        .build()));
    }

    private User requireUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IntegrityNotFoundException("Workspace not found");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IntegrityNotFoundException("Workspace not found"));
    }
}
