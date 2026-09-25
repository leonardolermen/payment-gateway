package com.gateway.providers.itau.pix.dto;

/** PUT /pix/{e2eid}/devolucao/{id} body. {@code natureza} is left out: absent means ORIGINAL (schema). */
public record DevolucaoRequest(String valor) {}
