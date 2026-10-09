-- V105: the non-secret part of the stored payload (client_id, pix_key, certificate_pem, ...), kept
-- in the clear next to secrets_set so the panel can show the form filled in without the GET ever
-- decrypting the blob. Secrets never land here: the writer filters by the provider's secret fields.
ALTER TABLE merchants.provider_credentials
    ADD COLUMN public_fields JSONB NOT NULL DEFAULT '{}'::jsonb;
