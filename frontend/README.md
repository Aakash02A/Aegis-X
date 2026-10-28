# Aegis-X Frontend Architecture & UI Guide

The Aegis-X frontend is an enterprise-grade, high-performance Single Page Application (SPA) built for cross-border trade compliance, remittance tracking, and payment integrity reconciliation.

It is engineered with **zero external CDN dependencies** (100% self-hosted webfonts and Font Awesome vector icons) and adheres strictly to a clean, editorial aesthetic inspired by modern fintech leaders.

---

## 📁 Directory Structure

```
frontend/
├── index.html                   # Public landing page & product narrative
├── login.html                   # Session authentication portal
├── register.html                # Merchant / exporter registration
├── dashboard.html               # Merchant console (remittances, KPIs, creation modal)
├── transaction.html             # Transaction workspace (lifecycle stepper, OCR, audit log)
├── compliance.html              # Compliance officer investigation queue
├── settlement.html              # Settlement desk (payout rails, retry, reconciliation)
├── operations.html              # System observability (live Actuator health & metrics)
├── integrity*.html              # Payment Integrity console (9 specialized audit views)
├── css/
│   ├── animations.css           # Micro-interactions, fade-ins, modal transitions
│   ├── components.css           # Design tokens, button classes, badges, cards, tables
│   ├── fonts.css                # Self-hosted @fontsource font-face declarations
│   ├── icons.css                # Self-hosted Font Awesome 6.7.2 vector icon mappings
│   └── tailwind.css             # Tailwind base directives
├── js/
│   ├── api.js                   # Unified HTTP client (JWT injection, correlation IDs, envelopes)
│   ├── auth.js                  # Session storage auth manager & RBAC route guards
│   ├── dashboard.js             # Merchant dashboard controller & remittance creation
│   ├── compliance.js            # Compliance queue & justification decisioning
│   ├── transaction.js           # Transaction details, stepper & timeline controller
│   ├── settlement.js            # Settlement reconciliation & retry controller
│   ├── operations.js            # Live Actuator health/metrics polling controller
│   ├── integrity.js             # Payment Integrity bounded context UI engine
│   ├── upload.js                # Drag-and-drop multipart evidence uploader
│   ├── ocr.js                   # OCR extraction status handler
│   ├── ai.js                    # AI advisory display (with explicit non-binding disclosure)
│   ├── policy.js                # Deterministic policy violation viewer
│   ├── audit.js                 # Immutable audit event feed viewer
│   ├── notifications.js         # WhatsApp & webhook alert history viewer
│   └── utils.js                 # Currency, date, and status formatting utilities
└── test/
    └── frontend-test.js         # 88-point automated contract & security verification suite
```

---

## 🎨 Design System & Visual Tokens

The frontend uses an **Obsidian, Paper, and Cyan/Rust** visual hierarchy with typography pinned via `@fontsource`:

| Token | CSS Variable | Hex / Value | Usage |
| :--- | :--- | :--- | :--- |
| **Obsidian** | `--bg-obsidian` / `--color-ink` | `#0f1419` / `#131920` | Dark sidebar, primary headers, high-contrast badges |
| **Paper** | `--color-paper` | `#faf8f5` | Main content background, editorial warmth |
| **Cyan** | `--brand-cyan` | `#00d2b4` | Primary brand accent, verified status, active states |
| **Rust** | `--color-rust` | `#c85a32` | Critical action buttons, brand monogram accent |
| **Olive Deep** | `--color-olive` | `#2d4a3e` | Compliance pass states, healthy actuator indicators |

### Typography Hierarchy
* **Headings:** *Fraunces* (Warm editorial serif) & *Urbanist* (Modern geometric sans)
* **Body / UI:** *Inter* & *Work Sans* (High legibility at 12–14px)
* **Code / Financial Figures:** *JetBrains Mono* (Fixed-width numeric alignment)

---

## 🔐 Role-Based Access Control (RBAC) Matrix

Every protected page executes an immediate authorization check against the backend `/auth/me` endpoint:

| Page | Allowed Roles | Redirect on Unauthorized |
| :--- | :--- | :--- |
| `dashboard.html` | `MERCHANT`, `ADMIN` | `login.html` |
| `compliance.html` | `COMPLIANCE_OFFICER`, `ADMIN` | `login.html` |
| `settlement.html` | `SETTLEMENT_OPERATOR`, `COMPLIANCE_OFFICER`, `ADMIN` | `login.html` |
| `operations.html` | `ADMIN` | `login.html` |
| `transaction.html` | All Authenticated Users | `login.html` |
| `integrity*.html` | All Authenticated Users (scoped by workspace) | `login.html` |

---

## 🛠️ Build & Development Commands

From the `frontend/` directory:

```bash
# 1. Install pinned dependencies (Vite, Tailwind, Fonts, FontAwesome)
npm install

# 2. Start local Vite development server (http://127.0.0.1:3000)
npm run dev

# 3. Run automated contract and security verification (88 checks)
npm test

# 4. Compile optimized production distribution bundle (dist/)
npm run build

# 5. Preview the compiled production build
npm run preview
```

---

## 🛡️ Security & Privacy Guardrails

1. **Zero Runtime CDNs:** All stylesheets, fonts, and icons are bundled locally into `dist/`. No external requests are made to unpkg, jsdelivr, or Google Fonts.
2. **Session-Scoped Tokens:** JWT tokens are stored exclusively in `sessionStorage` (cleared when the browser tab is closed).
3. **Correlation Tracking:** Every API request automatically generates and attaches an `X-Correlation-ID` header.
4. **Enveloped Error Handling:** Any backend error envelope (`success: false`) is trapped and displayed with user-friendly actionable feedback.

