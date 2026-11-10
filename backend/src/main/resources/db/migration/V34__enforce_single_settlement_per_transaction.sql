ALTER TABLE settlements
    ADD CONSTRAINT uq_settlements_transaction_id UNIQUE (transaction_id),
    DROP CHECK chk_payment_processor_valid,
    ADD CONSTRAINT chk_payment_processor_valid CHECK (payment_processor IN (
        'SWIFT', 'RIPPLE', 'VISA_DIRECT', 'MASTERCARD_SEND',
        'ACH', 'SEPA', 'FASTER_PAYMENTS', 'MOCK', 'RAZORPAYX', 'SIMULATED'
    ));
