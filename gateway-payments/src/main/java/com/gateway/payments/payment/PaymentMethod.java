package com.gateway.payments.payment;

/** How the payer pays. BOLECODE is one method with two settlement paths (QR or barcode); there is no boleto without Pix (spec 2026-09-25). */
public enum PaymentMethod { PIX, BOLECODE }
