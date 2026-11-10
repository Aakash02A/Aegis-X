ALTER TABLE transactions
    ADD COLUMN razorpay_order_id VARCHAR(50) NULL,
    ADD CONSTRAINT uq_transactions_razorpay_order_id UNIQUE (razorpay_order_id);
