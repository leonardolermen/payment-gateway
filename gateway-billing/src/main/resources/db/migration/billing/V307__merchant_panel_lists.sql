-- The panel's lists page by id within one merchant and environment (ids are ULIDs, so id order is
-- creation order). idx_orders_merchant_created sorts by created_at, which the cursor does not use.
CREATE INDEX idx_orders_merchant_env_id ON billing.orders (merchant_id, environment, id DESC);
CREATE INDEX idx_customers_merchant_env_id
    ON billing.customers (merchant_id, environment, id DESC) WHERE deleted_at IS NULL;
