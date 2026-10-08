-- The panel lists subscriptions by cursor in one merchant and environment, and an order history per
-- customer; both page by id (ULIDs, so id order is creation order), as V307 did for orders.
CREATE INDEX idx_subscriptions_merchant_env_id
    ON billing.subscriptions (merchant_id, environment, id DESC);
CREATE INDEX idx_orders_merchant_customer_id
    ON billing.orders (merchant_id, customer_id, id DESC) WHERE customer_id IS NOT NULL;
