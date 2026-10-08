package com.gateway.app.api.team;

import com.gateway.app.api.team.dto.ChangeRoleRequest;
import com.gateway.app.api.team.dto.InviteRequest;
import com.gateway.app.api.team.dto.PendingInviteResponse;
import com.gateway.app.api.team.dto.TeamMemberResponse;
import com.gateway.app.api.team.dto.TeamResponse;
import com.gateway.app.security.Actor;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.usertoken.UserTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store's team. Reading is for every role, writing for owners (the session filter's route
 * table); one's own account goes through /v1/me, so an owner cannot demote or remove herself here.
 */
@RestController
public class TeamController {
  private final UserService users;
  private final UserTokenService tokens;
  private final TeamService team;

  public TeamController(UserService users, UserTokenService tokens, TeamService team) {
    this.users = users;
    this.tokens = tokens;
    this.team = team;
  }

  @GetMapping("/v1/merchant/users")
  public TeamResponse list() {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return new TeamResponse(
        users.listByMerchant(merchantId).stream().map(TeamMemberResponse::of).toList(),
        tokens.openInvites(merchantId).stream().map(PendingInviteResponse::of).toList());
  }

  @PostMapping("/v1/invites")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void invite(@RequestBody InviteRequest request) {
    request.validate();

    team.invite(
        MerchantContext.current().merchantId(), request.emailAddress(), request.parsedRole());
  }

  @PatchMapping("/v1/merchant/users/{id}")
  public TeamMemberResponse changeRole(
      @PathVariable("id") String userId, @RequestBody ChangeRoleRequest request) {
    request.validate();
    refuseOwnAccount(userId);

    MerchantId merchantId = MerchantContext.current().merchantId();

    return TeamMemberResponse.of(users.changeRole(merchantId, userId, request.parsedRole()));
  }

  @DeleteMapping("/v1/merchant/users/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable("id") String userId) {
    refuseOwnAccount(userId);

    team.remove(MerchantContext.current().merchantId(), userId);
  }

  private static void refuseOwnAccount(String userId) {
    if (caller().userId().equals(userId)) {
      throw new IllegalArgumentException("use /v1/me for your own account");
    }
  }

  private static Actor.User caller() {
    if (MerchantContext.current().actor() instanceof Actor.User user) {
      return user;
    }

    throw new IllegalStateException("the session filter lets only users reach the team routes");
  }
}
