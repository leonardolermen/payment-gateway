# Decisões

Append-only. Cada entrada: decisão, alternativa rejeitada, custo de estar errada.

## 2026-09-24 — Todo o código em inglês
Identificadores, comentários, mensagens, commits e README. Rejeitado: português como no Barrier e no
agent-orchestrator. Motivo: produto para terceiros e lib compartilhada com API pública em inglês. Custo se
errado: nenhum técnico; os docs de spec/plano continuam em português.

## 2026-09-24 — API key com SHA-256+pepper, não bcrypt
Chave de 130 bits aleatórios: força bruta é impossível com hash rápido; bcrypt custaria ~100 ms por
requisição. Rejeitado: bcrypt/argon2. Custo se errado: nenhum enquanto a chave for aleatória; se um dia
aceitarmos chave escolhida pelo merchant, a decisão cai.

## 2026-09-24 — Envelope AES-256-GCM com DEK por linha, mestra em env
Rejeitado: cifrar tudo direto com a mestra (rotacionar = recifrar tudo); KMS já no MVP (uma instância,
um operador). Custo se errado: a mestra em env é um segredo a mais para vazar; migrar para KMS é trocar
`MasterKey` e recifrar 32 bytes por linha.

## 2026-09-24 — Rate limit em memória
Uma instância. Rejeitado: Redis no MVP. Custo se errado: com duas instâncias o limite vira 2x até
trocar por bucket distribuído.

## 2026-09-24 — Admin key vazia fecha o admin
Rejeitado: "sem chave = dev aberto". Custo se errado: nenhum; dev exporta uma variável.
