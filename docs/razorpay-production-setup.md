# Razorpay and RazorpayX setup

The application has two separate Razorpay integrations:

- **Razorpay Orders/Checkout** charges a payer. An authenticated merchant creates an order with
  `POST /api/v1/payments/orders` and a `transactionId`; the response supplies the public checkout
  key, order ID, amount (minor units), and currency. The transaction's provider order ID is persisted
  and payment webhooks are bound to that exact ID. Notes and merchant-supplied references are not
  accepted as a substitute.
- **RazorpayX Payouts** sends an approved INR settlement to a bank account. It is the only live
  settlement processor currently implemented. Other network enum values are not advertised or
  submitted to a synthetic processor.

## Required configuration

Set these as deployment secrets/environment variables; do not commit real values or send them in
chat:

| Variable | Purpose |
|---|---|
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` | Razorpay Orders API and Checkout |
| `RAZORPAY_WEBHOOK_SECRET` | HMAC verification for payment and payout events sent to `/api/webhooks/razorpay` |
| `RAZORPAYX_ENABLED` | Explicitly enables RazorpayX payout submission; defaults to `false` |
| `RAZORPAYX_KEY_ID` / `RAZORPAYX_KEY_SECRET` | RazorpayX Payouts API credentials |
| `RAZORPAYX_ACCOUNT_NUMBER` | Your RazorpayX current-account number used as the payout source |
| `RAZORPAYX_PAYOUT_MODE` | `NEFT`, `RTGS`, or `IMPS`; defaults to `NEFT` |
| `RAZORPAY_API_BASE_URL` / `RAZORPAYX_API_BASE_URL` | Defaults to `https://api.razorpay.com/v1` |

Use the matching Test Mode credential pair and webhook secret in non-production. Live credentials must
be installed only in the production secret manager after the relevant Razorpay and RazorpayX accounts
are activated. `RAZORPAYX_ENABLED=true` is not sufficient by itself: all credentials and the source
account number must also be present. Payout requests fail closed if configuration is incomplete.

## Provider dashboard checklist

1. Complete Razorpay/RazorpayX onboarding, KYC, account activation, and any required payout approval
   workflow. Confirm your dashboard enables the `vendor bill` payout purpose and selected transfer
   mode.
2. Allowlist the backend's stable egress IP with RazorpayX before testing payout submission.
3. Configure the public webhook URL as `https://<your-api-host>/api/webhooks/razorpay`, set the same
   secret as `RAZORPAY_WEBHOOK_SECRET`, and subscribe to `payment.captured`, `payment.failed`,
   `payout.pending`, `payout.initiated`, `payout.processed`, `payout.failed`, `payout.rejected`,
   `payout.reversed`, `payout.updated`, and `payout.queued` where supported.
4. Verify webhook signatures and idempotent redelivery in Test Mode, including late/out-of-order
   payout notifications. The backend checks payout ID, settlement reference, amount, and currency
   before applying a status.
5. Apply Flyway migrations on deployment. `V31` adds the unique persisted Razorpay order ID used for
   capture matching; `V32` registers the payment-order audit event.
6. Run a small provider-approved test transaction and payout end-to-end before enabling live
   processing. Reconciliation fetches the provider payout and marks it reconciled only after an exact
   provider-side match.

The beneficiary bank account and IFSC are sent to RazorpayX to create a contact/fund account and are
not stored in Aegis-X. Only provider-generated contact/fund-account IDs and payout metadata are
persisted. Keep API request/response logging disabled or scrubbed for these routes.

Provider activation, KYC, allowlisting, operational approval controls, jurisdictional authorization,
and production monitoring remain deployment responsibilities; code configuration alone does not
constitute production approval or regulatory compliance.
