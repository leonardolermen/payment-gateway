package com.gateway.app.api.team.dto;

import java.util.List;

/** GET /v1/merchant/users: who is in the store, and who was invited and has not come in yet. */
public record TeamResponse(List<TeamMemberResponse> users, List<PendingInviteResponse> invites) {}
