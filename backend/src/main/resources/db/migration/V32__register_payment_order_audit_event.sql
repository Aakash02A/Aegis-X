INSERT INTO audit_event_types (event_type, description, module)
VALUES ('PAYMENT_ORDER_CREATED', 'Razorpay order created or recovered for a transaction', 'RAZORPAY')
AS new_row
ON DUPLICATE KEY UPDATE description = new_row.description;
