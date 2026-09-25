package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Payer;
import java.time.LocalDate;

/** {@code nossoNumero} is ours (8 digits): it is what lets a retry after a timeout ask the bank whether the boleto exists. */
public record BoletoIssueRequest(String nossoNumero, Money amount, LocalDate dueDate, LocalDate paymentLimitDate, Payer payer, String description) {}
