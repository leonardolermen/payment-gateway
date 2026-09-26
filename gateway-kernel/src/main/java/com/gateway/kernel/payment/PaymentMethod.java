package com.gateway.kernel.payment;

/**
 * How the payer pays. BOLECODE is one method with two settlement paths (QR or barcode); there is no
 * boleto without Pix (spec 2026-09-25).
 *
 * <p>In the kernel, not in payments: it is the vocabulary payments and providers share — a provider
 * declares which method it serves ({@code MethodProvider.method()}), and the kernel may not import
 * payments.
 */
public enum PaymentMethod {
  PIX,
  BOLECODE
}
