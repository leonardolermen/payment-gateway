# Provedores em self-service — design (B4, parte 1)

Data: 2026-10-09. Assume B3 (`feat/users-and-sessions`, PR #33): sessões, papéis e `RoleRoutes`. É a
metade "provedores" do item B4 de `ROADMAP-PAINEL.md`; chaves de API com escopo e rotação com
convivência ficam para a parte 2 (Plano H §3). Lado do front: F5 em
`payment-gateway-web/docs/superpowers/specs/2026-10-08-produto-completo-design.md` §8 (Provedores).
Decisões do usuário (2026-10-08/09): o lojista cadastra as próprias credenciais, por ambiente, com
"testar conexão"; sem aprovação pelo operador; só o **dono** mexe.

## 1. O que existe
`merchants/credential/ProviderCredentialService` guarda o payload JSON cifrado (envelope, AAD =
merchant|provider|ambiente) e só `decrypt` o abre; os parsers `ItauCredentials` e `CieloCredentials`
em `providers` validam a forma (`requireProductionShape` para LIVE) e calculam um `fingerprint`
(SHA-256 do JSON). Hoje só `PUT /v1/admin/merchants/{id}/providers/{provider}/credentials` escreve, e
`PUT …/notification-key` guarda o header fixo da Cielo. A URL de webhook que o lojista cadastra no
banco (`WebhookMtlsProperties.inboundWebhookUrl(token)`) só aparece na resposta admin. `/v1/providers/**`
é a borda **que o banco chama**, sem autenticação de merchant (`ProtectedRoutes.requiresApiKey` a
exclui) — por isso nenhuma rota do lojista pode viver ali.

## 2. Rotas (`api/provider/`, todas sob `/v1/merchant/providers`, dono e só sessão)
`RoleRoutes`: `/v1/merchant/**` já exige `OWNER`; `userOnly` ganha `/v1/merchant/providers` (uma
chave de API vazada não pode trocar a credencial do banco — `403 USER_SESSION_REQUIRED`). O prefixo
morto `/v1/providers` sai de `OWNER_PREFIXES`.

| rota | corpo | resposta |
|---|---|---|
| `GET /v1/merchant/providers` | — | `{environment, inbound_webhook_url, providers:[{provider: "ITAU"\|"CIELO", methods: ["PIX","BOLECODE"]\|["CARD"], configured, updated_at, fingerprint, secrets_set: {client_secret: bool, …}, fields: {client_id: "…", pix_key: "…", …} (só os campos públicos; `{}` sem credencial), last_test: {ok, detail, checked_at}\|null, notification_key_set: bool\|null}]}` |
| `PUT /v1/merchant/providers/{provider}/credentials` | `{payload: {…}}` | `204`; `422 PROVIDER_CREDENTIALS_INVALID` com `field` (nome como o cliente enviou, ex. `private_key_pem`); `400` provider desconhecido |
| `POST /v1/merchant/providers/{provider}/test` | — | `200 {ok, detail, checked_at}`; `404 PROVIDER_CREDENTIALS_MISSING` sem credencial no ambiente |
| `PUT /v1/merchant/providers/cielo/notification-key` | `{key}` | `204`; `400` vazio ou > 1500 |

O **ambiente** é o do header `X-Environment` (uma sessão), nunca do corpo. `fingerprint` devolve só os
8 primeiros hex — é para o painel dizer "definido em …" e detectar mudança, não para comparar fora.

### 2.1 Campos por provedor (o que `payload` aceita)
- **ITAU**: `client_id`, `client_secret`*, `pix_key`, `beneficiary_id`, `wallet_code`, `species_code`;
  em LIVE também `x_itau_apikey`*, `certificate_pem`, `private_key_pem`*. A validação é a dos parsers
  que já existem (`ItauCredentials.parse` + `requireProductionShape()` quando o ambiente é LIVE);
  o erro do parser vira `PROVIDER_CREDENTIALS_INVALID` com o `field`.
- **CIELO**: `merchant_id`, `merchant_key`*.

Campos marcados * são **segredos**: nunca voltam no `GET` (só `secrets_set`), e num `PUT` podem ser
**omitidos** para manter o valor já guardado — o serviço faz merge sobre o payload decifrado da
credencial existente antes de validar e cifrar. Enviar `""` apaga. Assim a tela edita a chave Pix sem
obrigar a colar o certificado de novo.

A lista de campos de cada provedor é fechada: uma chave fora dela (`clientSecret`, `client_secret ` com
espaço) é recusada com `422 PROVIDER_CREDENTIALS_INVALID` e `field` = a chave como veio, antes do parser
— senão um segredo digitado errado passaria como campo público e ficaria guardado (e exibido) em claro.

## 3. Testar conexão
Port novo no kernel: `interface CredentialProbe { String providerId(); ProbeResult probe(ProviderCredentials) }`
com `record ProbeResult(boolean ok, String detail)`; `providers` implementa um por banco e
`ProvidersConfiguration` expõe os beans; `app` os indexa por nome (`CredentialProbes`).
- **Itaú**: `ItauTokenClient.tokenFor(creds, ItauEndpoints.forEnvironment(env), trustStore)` —
  token obtido = `ok`; 401/403 = "credencial recusada pelo Itaú"; certificado inválido = "certificado
  ou chave privada inválidos"; timeout = "Itaú não respondeu". Em LIVE o probe exige o shape de
  produção antes de sair para a rede.
- **Cielo**: `GET /1/sales/{uuid nulo}` com a credencial — `404` = autenticou (`ok`); `401` =
  recusada; outro = detalhe genérico.
Nenhum probe cria cobrança nem muda estado no banco. O resultado é gravado na própria credencial
(`last_test_ok`, `last_test_detail`, `last_test_at`, migração `V104__provider_credential_test.sql`) para
o painel mostrar "conectado em …"/"falhou em …" sem repetir a chamada. Cada `test` é auditado.

## 4. Segurança
- Segredo nunca é logado nem ecoado: `PUT` responde `204`; a validação devolve só o nome do campo;
  o `Masker` já cobre `provider_requests`.
- `last_test_detail` nunca contém o corpo do banco — só a frase escolhida pelo probe.
- `GET` traz `inbound_webhook_url` (a que o lojista cadastra no Itaú) — não é segredo, é o token de
  webhook por merchant que já existe.
- Audit lines (`gateway.audit.account`, como B3): `provider.credentials.set {provider, env}`,
  `provider.test {provider, env, ok}`, `provider.notification_key.set`.

## 5. Testes
Integração (`ProvidersApiIntegrationTest`, WireMock): dono grava Itaú TEST → `GET` mostra
`configured`, `secrets_set.client_secret = true`, nunca o segredo; `PUT` sem `client_secret` mantém o
anterior (o `decrypt` prova); `""` apaga; LIVE sem certificado → 422 com `field`; `test` Itaú com o
WireMock do token → `ok` e `last_test` no `GET`; token 401 → `ok: false` com a frase; Cielo 404 → ok,
401 → falhou; FINANCE → 403 `FORBIDDEN_FOR_ROLE`; chave `gk_` → 403 `USER_SESSION_REQUIRED`;
notification-key > 1500 → 400. Unidade: `CredentialProbes` indexa um por provedor e recusa duplicado;
merge de segredos (`SecretMerge`) mantém, substitui e apaga por campo.

## 6. Decisões (para o `DECISOES.md`)
1. **Rotas do lojista em `/v1/merchant/providers`, não em `/v1/providers`.** `/v1/providers/**` é o
   banco chamando, sem autenticação de merchant. Custo: dois prefixos parecidos; o nome do pacote
   (`api/provider`) e o comentário no `ProtectedRoutes` dizem qual é qual.
2. **Só sessão de dono grava credencial; chave de API não.** Rejeitado: permitir à chave — uma chave
   vazada trocaria a conta beneficiária. Custo: integração por script precisa de um usuário.
3. **Segredo omitido no `PUT` mantém o anterior.** Rejeitado: `PUT` substitui tudo — a tela obrigaria
   a colar certificado e chave a cada edição de campo. Custo: merge sobre o payload decifrado, mais
   um lugar que abre a credencial (só dentro do serviço, nunca sai).
4. **Probe como port do kernel implementado em `providers`.** Rejeitado: o app chamar o token client
   direto — amarra o app ao vocabulário do banco (a regra "Itau só em providers"). Custo: uma
   interface a mais.
5. **Resultado do teste gravado na credencial.** Rejeitado: testar a cada `GET` — uma chamada ao banco
   por abertura de tela. Custo: três colunas e um "testado em" que pode envelhecer.
