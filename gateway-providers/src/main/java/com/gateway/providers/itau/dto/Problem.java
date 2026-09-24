package com.gateway.providers.itau.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** RFC 7807 error body, Bacen flavour ({@code violacoes[]}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Problem(String type, String title, Integer status, String detail, List<Map<String, String>> violacoes) {}
