# Roadmap do back para o painel do lojista

Data: 2026-10-08. O desenho do painel inteiro — telas, papéis, contratos de API — está em
`payment-gateway-web/docs/superpowers/specs/2026-10-08-produto-completo-design.md` (§9 é a fila).
Este arquivo é só a fila do lado do gateway: uma PR por linha, na ordem, cada uma com spec própria
em `docs/superpowers/specs/` quando for arquitetural (B3 e B4 são; as outras são bounded).

| # | PR | Estado | Destrava |
|---|---|---|---|
| B1 | Listagens: `GET /v1/subscriptions` geral, `orders?customer_id`, `customers?q` | PR #30 | F2 Clientes, F3 Assinaturas |
| B2 | Assinatura por link (`specs/2026-10-07-assinatura-por-link-design.md`) | plano a escrever | F3: cartão pelo link |
| B3 | Usuários e sessão: `auth/*`, `/v1/me`, papéis dono/financeiro/leitura, verificação de e-mail, convites, refresh em cookie | spec a escrever | F4 Conta, ambiente, equipe |
| B4 | Chaves e provedores self-service: `/v1/merchant/api-keys` (escopos, rotação com convivência), `/v1/providers/{itau,cielo}/credentials` + `test` | spec a escrever (absorve §3 do Plano H) | F5 Configurações |
| B5 | `GET /v1/summary` e `onboarding` no `/v1/me` | bounded | F6 Início |
| B6 | `GET /v1/orders` com `method/from/to/q`; `GET /v1/refunds`; `GET /v1/orders/{id}/timeline` | bounded | F7 Cobranças |
| B7 | Clientes: `notes`, `tags`, importação CSV. Assinaturas: pausar/retomar, trocar plano, MRR, `description`, `active_subscribers` | bounded | F8 |
| B8 | Contestações: evidência (multipart), `due_at`. Webhooks: `test`, `PATCH` de endpoint, `attempts[]`, filtros de entregas | bounded | F9 |
| B9 | `PATCH /v1/merchant` + `branding` no checkout; `export.csv` | bounded | F10 |

Ordem: B2 → B3 → B4 → B5 → B6 → B7 → B8 → B9. Nada aqui muda resposta existente sem dizer; o que
muda contrato entra em `DECISOES.md`.
