package com.gateway.providers.itau.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * A received Pix, inside a cob or a webhook. {@code txid} is absent for static-QR and key-transfer
 * payments; {@code infoPagador} arrives as a number in the bank's own list example, so it is read
 * as a String through Jackson's scalar coercion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PixItem(String endToEndId, String txid, String valor, Instant horario, String infoPagador, List<DevolucaoResponse> devolucoes) {}
