# Aegis-X AI & Machine Learning Pipeline

This module specifies the AI advisory engine, OCR pipelines, and prompt templates powering Aegis-X.

---

## 🏛️ The Golden Principle

> **"AI is an Advisor. Java is the Sole Compliance Authority."**

* **What AI Does:** Parses raw OCR text, extracts structured entity representations, matches cross-border counterparties, flags potential ambiguities, and produces confidence ratings.
* **What AI Never Does:** Transitions transaction states, clears compliance holds, disburses funds, or overrides deterministic rules.

---

## 📁 Directory Structure

```
ai-ml/
├── prompts/
│   └── regulatory-advisory-prompt.json    # System prompt contract for OpenAI / Spring AI
└── ocr/
    └── document-extraction-spec.json      # Structured schema for Apache Tika & Tesseract OCR outputs
```

---

## ⚙️ Model & Fallback Behavior

* **Model:** OpenAI `gpt-4o-mini` (configured with `temperature = 0.0` for deterministic structured JSON generation).
* **Spring Boot Integration:** Wrapped in `Spring AI` with custom `BlankOpenAiKeyEnvironmentPostProcessor` so that if no `OPENAI_API_KEY` is supplied, the system boots normally and gracefully delegates decision authority directly to the deterministic Java policy engine without throwing errors.

