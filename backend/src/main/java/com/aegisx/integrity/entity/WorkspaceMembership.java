package com.aegisx.integrity.entity;

import com.aegisx.auth.entity.User;
import com.aegisx.common.entity.BaseEntity;
import com.aegisx.integrity.domain.WorkspaceMemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "workspace_members", uniqueConstraints = @UniqueConstraint(
        name = "uq_workspace_members_user_workspace",
        columnNames = {"user_id", "workspace_id"}))
public class WorkspaceMembership extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private WorkspaceMemberRole role;
}
