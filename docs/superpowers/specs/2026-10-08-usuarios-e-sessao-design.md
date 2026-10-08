# Usuários e sessão — design (B3 do roadmap do painel)

Data: 2026-10-08. Assume Planos A–G, checkout público e PR #30 em `main`. É o item B3 de
`docs/superpowers/ROADMAP-PAINEL.md`; o lado do front está em
`payment-gateway-web/docs/superpowers/specs/2026-10-08-produto-completo-design.md` (§1, §2, §8).
Decisões do usuário (2026-10-08): login por e-mail e senha; vários usuários por loja com papel (dono,
financeiro, leitura) e convite por e-mail; e-mail por **SMTP** com fallback de log; senha com
**Argon2id** via `spring-security-crypto`; **tokens opacos com hash no banco**, não JWT. Fora: 2FA,
SSO, escopos de chave e chaves criadas pelo dono (B4 / Plano H), `audit_log` e rate limit persistido
(Plano H), a mesma pessoa em duas lojas.

## 1. Onde mora
- `gateway-merchants/.../merchants/user/` — `User`, `Role`, `UserService` + `persistence/`.
- `merchants/session/` — `Session`, `SessionService` (emite, renova, revoga).
- `merchants/usertoken/` — `UserToken` (`VERIFY_EMAIL | RESET_PASSWORD | INVITE`), `UserTokenService`.
- `merchants/mail/` — port `Mailer`, `SmtpMailer`, `LoggingMailer`, templates.
- `gateway-app/security/UserSessionFilter`, `security/RoleRoutes`; `api/auth/AuthController`,
  `api/me/MeController`, `api/team/TeamController` (+ `dto/`).

Sem Spring Security: só `spring-security-crypto` pelo `Argon2PasswordEncoder` (m = 64 MB, t = 3,
p = 1). `spring-boot-starter-mail` para o SMTP. ArchUnit: `merchants` continua sem importar
`payments`; o envio assíncrono usa o `JobType` de `payments` **pela cola em `gateway-app`**, como o
`ProviderWiring` faz.

## 2. Tabelas (`V103__users_and_sessions.sql`, schema `merchants`)
```
users(id CHAR(26) PK, merchant_id CHAR(26) FK, name VARCHAR(120), email VARCHAR(254),
      email_normalized VARCHAR(254) UNIQUE, password_hash VARCHAR(200),
      role VARCHAR(10) OWNER|FINANCE|READONLY, email_verified_at TIMESTAMPTZ, last_login_at,
      created_at, updated_at, deleted_at)
sessions(id CHAR(26) PK, user_id FK, access_hash CHAR(64), refresh_hash CHAR(64) UNIQUE,
         access_expires_at, refresh_expires_at, ip VARCHAR(45), user_agent VARCHAR(200),
         created_at, last_used_at, revoked_at)
user_tokens(id CHAR(26) PK, user_id FK NULL, merchant_id FK, kind VARCHAR(16),
            token_hash CHAR(64) UNIQUE, payload JSONB, expires_at, used_at, created_at)
```
Índices: `sessions(access_hash)`, `sessions(user_id) WHERE revoked_at IS NULL`,
`user_tokens(token_hash)`. `email_normalized` = trim + minúsculas; único **globalmente**. Hash de
token = SHA-256 com o pepper das chaves de API; `payload` do convite = `{role}`.

## 3. Sessão
- `login` cria uma linha e devolve `{access_token, expires_in: 900}` no corpo e o refresh em
  `Set-Cookie: gw_refresh=…; HttpOnly; Secure; SameSite=None; Path=/v1/auth; Max-Age=2592000`.
  Access e refresh são 32 bytes aleatórios em base64url, com prefixos `gs_` e `gr_`.
- `refresh` **rotaciona**: o refresh antigo morre e um novo vai no cookie, com access novo. Reuso de
  um refresh já rotacionado revoga a sessão inteira (sinal de roubo) e responde `401 SESSION_EXPIRED`.
- `logout` revoga a sessão e limpa o cookie. `DELETE /v1/me/sessions/others` revoga as demais.
  Trocar ou redefinir senha revoga todas (a atual sobrevive só na troca).
- `last_used_at` é escrito no máximo uma vez por minuto por sessão: uma escrita por request seria o
  custo errado para um `GET`.

## 4. Quem está chamando
```java
record Current(MerchantId merchantId, ApiKeyEnvironment environment, Actor actor)
sealed interface Actor permits ApiKeyActor, UserActor
record ApiKeyActor(String apiKeyId) implements Actor
record UserActor(String userId, Role role) implements Actor
```
`UserSessionFilter` roda antes de `ApiKeyAuthFilter`: `Bearer gk_…` segue para a chave; `Bearer
gs_…` é sessão — acha por `access_hash`, confere `access_expires_at` e `revoked_at`, carrega usuário
(não apagado) e merchant (`ACTIVE`). O **ambiente** de uma sessão vem do header
`X-Environment: TEST|LIVE` (default `TEST`); `LIVE` com e-mail não verificado → `403
EMAIL_NOT_VERIFIED`. CORS passa a permitir `X-Environment` e `credentials` **só** em `/v1/auth/*`.

**Papel → rota** (`security/RoleRoutes`, tabela declarativa, consultada após autenticar):

| mínimo | rotas |
|---|---|
| `READONLY` | todo `GET` |
| `FINANCE` | `POST/PATCH/DELETE` em `orders`, `payments`, `customers` (exceto `DELETE`), `plans`, `subscriptions`, `refunds`, `disputes`, `webhooks/deliveries/**` |
| `OWNER` | `webhooks/endpoints/**`, `merchant/**`, `providers/**`, `installment-settings`, `invites`, `DELETE /v1/customers/{id}` |

Insuficiente → `403 FORBIDDEN_FOR_ROLE` com `required_role`. Chave de API não passa por papel
(escopos são do B4/Plano H; hoje tudo liberado, como já é). Rate limit de sessão: balde por `userId`
no `RateLimitFilter` de hoje. Rotas `/v1/auth/*` sem sessão: balde por IP no
`CheckoutRateLimitFilter` (10/min em `login`, `signup`, `password/forgot`).

**Auditoria:** B3 não cria o `audit_log`; o `actor` já é o `actor_kind/actor_id` dele. Login, logout,
troca de senha, convite e mudança de papel vão para log estruturado agora.

## 5. Fluxos (`/v1/auth/*`, sem autenticação)
| rota | corpo | resposta |
|---|---|---|
| `POST signup` | `{store_name, name, email, password}` | `201 {access_token, expires_in}` + cookie; `409 EMAIL_TAKEN`; `422 WEAK_PASSWORD` (< 10) |
| `POST login` | `{email, password}` | `200` idem; `401 INVALID_CREDENTIALS` (mesma resposta para e-mail inexistente; o Argon2 roda sobre um hash fixo nesse caso) |
| `POST refresh` | só o cookie | `200` idem, cookie rotacionado; `401 SESSION_EXPIRED` |
| `POST logout` | — | `204`, cookie limpo |
| `POST password/forgot` | `{email}` | `202` sempre; token `RESET_PASSWORD` (1 h) se existir |
| `POST password/reset` | `{token, password}` | `204`; `410 TOKEN_EXPIRED` |
| `POST email/verify` | `{token}` | `204`; `410 TOKEN_EXPIRED` |
| `POST email/resend` | — (autenticado) | `202`; 1 por 5 min, `429` |
| `POST invite/accept` | `{token, name, password}` | `201` + sessão; `410 TOKEN_EXPIRED` |

`signup` cria merchant `ACTIVE`, usuário `OWNER`, chaves TEST (como `scripts/dev_merchant.py`),
token `VERIFY_EMAIL` (24 h) e o e-mail. Tokens são de uso único: `used_at` na mesma transação do
efeito.

## 6. Conta e equipe (autenticado)
- `GET /v1/me → {user:{id, name, email, role, email_verified}, merchant:{id, name},
  onboarding:{email_verified, live_enabled}}` (o resto do `onboarding` vem no B5).
- `PATCH /v1/me {name}`; `POST /v1/me/password {current, new}` (revoga as outras sessões);
  `GET /v1/me/sessions` (id, ip, user_agent, created_at, last_used_at, `current`);
  `DELETE /v1/me/sessions/others`.
- `GET /v1/merchant/users` (nome, e-mail, papel, `last_login_at`, convites pendentes com
  `expires_at`); `POST /v1/invites {email, role}` (7 dias; reenviar substitui o token; e-mail já
  usuário → `409 EMAIL_TAKEN`); `PATCH /v1/merchant/users/{id} {role}`;
  `DELETE /v1/merchant/users/{id}` (soft, revoga sessões). O último `OWNER` ativo não muda de papel
  nem é removido: `409 LAST_OWNER`. Ninguém edita a si mesmo por essas rotas (`/v1/me` é o lugar).

## 7. E-mail
Port `Mailer.send(Email{to, subject, text, html})`. `SmtpMailer` com
`GATEWAY_MAIL_HOST/PORT/USERNAME/PASSWORD/FROM`; `LoggingMailer` quando `GATEWAY_MAIL_HOST` está
vazio — loga assunto, destinatário e **o link** em `local`/`test`, e só assunto e destinatário nos
demais perfis (o token nunca vai ao log em produção); WARN no boot dizendo que o e-mail está
desligado. Templates em texto + HTML mínimo, português, com `GATEWAY_PANEL_BASE_URL` para montar
`/verify/:token`, `/reset/:token`, `/invite/:token`. Envio **fora da transação** e assíncrono: job
`SEND_EMAIL` na tabela de jobs existente (retry e visibilidade de graça); o payload do job guarda o
token já montado no link, e o job não é reexecutado depois de `DONE` (regra de 2026-10-05).

## 8. Testes
Integração (Testcontainers + GreenMail): signup → e-mail chega com link → verify → `X-Environment:
LIVE` passa; login errado é 401 idêntico para e-mail inexistente e senha errada; refresh rotaciona e o
reuso do antigo revoga a sessão; reset derruba todas as sessões; `READONLY` em `POST /v1/orders` →
403 `FORBIDDEN_FOR_ROLE`; `FINANCE` em `POST /v1/webhooks/endpoints` → 403; último dono → 409;
`LIVE` sem verificação → 403; convite aceito entra com o papel certo e o token não aceita duas vezes;
chave `gk_` continua funcionando em tudo — **nenhum teste existente muda**.
`SessionsNeverHoldPlaintextTest` varre `users`, `sessions` e `user_tokens` por senha, access, refresh
e tokens em claro, como o teste PCI faz. Unidade: `RoleRoutes` (tabela), normalização de e-mail,
política de senha, `LoggingMailer` não loga token fora de `local`/`test`.

## 9. Decisões (para o `DECISOES.md`)
1. **Tokens opacos com hash no banco, não JWT.** Rejeitado: JWT assinado — lib nova, revogação exige
   lista negra, e "encerrar outras sessões" vira um problema. Custo: uma consulta por request, a
   mesma que a chave de API já faz.
2. **Refresh em cookie `HttpOnly`, access em memória no front.** Rejeitado: tudo em `localStorage`.
   Custo: CORS com credentials em `/v1/auth/*` e `SameSite=None`.
3. **Argon2id via `spring-security-crypto`, sem Spring Security.** Rejeitado: Spring Security inteiro
   — reescreveria a cadeia de filtros que já existe e funciona. Custo: um jar; a política de senha é
   nossa.
4. **E-mail único no sistema.** Rejeitado: por loja — "a mesma pessoa em duas lojas" exigiria seletor
   de loja no login, que o produto ainda não pediu. Custo: quem precisar disso usa dois e-mails até
   existir.
5. **SMTP com fallback de log, envio por job.** Rejeitado: API HTTP de um provedor (lock-in e mais
   um WireMock); envio síncrono na transação (um SMTP lento seguraria o signup). Custo: um `JobType`
   novo e o link no log em dev.
6. **Papel por tabela de rotas no filtro, não por anotação.** Rejeitado: `@PreAuthorize` — traria o
   Spring Security. Custo: rota nova precisa de linha na tabela; o teste de `RoleRoutes` cobra.
7. **Chave de API segue sem papel nem escopo no B3.** Rejeitado: aplicar a tabela de papéis às
   chaves — mudaria contrato de quem integra hoje. Custo: escopos ficam para o B4.
