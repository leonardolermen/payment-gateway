package com.gateway.app.api.auth;

import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.apikey.ApiKeyService;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store, TEST key, owner and verification e-mail in one transaction: a taken e-mail or a weak
 * password must not leave an ownerless merchant behind.
 */
@Component
public class SignupService {
  private final MerchantService merchants;
  private final ApiKeyService apiKeys;
  private final UserService users;
  private final AuthMailService mail;

  public SignupService(
      MerchantService merchants, ApiKeyService apiKeys, UserService users, AuthMailService mail) {
    this.merchants = merchants;
    this.apiKeys = apiKeys;
    this.users = users;
    this.mail = mail;
  }

  @Transactional
  public User signup(String storeName, String name, EmailAddress email, String password) {
    Merchant merchant = merchants.create(storeName);
    apiKeys.issue(merchant.id(), ApiKeyEnvironment.TEST);

    User owner = users.register(merchant.id(), name, email, Role.OWNER, password);
    mail.sendVerification(owner);

    return owner;
  }
}
