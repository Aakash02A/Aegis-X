# Aegis-X Automation & Utility Scripts

This directory houses cross-platform automation and maintenance scripts.

---

## 📁 Available Scripts

| Script | Platform | Description |
| :--- | :--- | :--- |
| **`build-all.bat`** | Windows CMD | Packages backend JAR (`mvn package`) and compiles Vite production frontend (`npm run build`). |
| **`build-all.sh`** | Linux / macOS Bash | Packages backend JAR and compiles Vite bundle in Unix environments. |
| **`verify-health.sh`** | Bash | Probes Spring Boot Actuator `/actuator/health` endpoint and validates database connectivity. |

