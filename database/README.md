# Aegis-X Database Module

This directory contains database schemas, Flyway migrations, and seed data for **MySQL 8.4 LTS**.

---

## 📁 Directory Structure

```
database/
├── schema/
│   └── schema.sql              # Consolidated MySQL 8.4 DDL baseline
├── migrations/
│   ├── V1__mysql_baseline.sql  # Core initial baseline
│   ├── V28__explicit_supersession_marker.sql
│   ├── V29__add_user_phone.sql
│   └── V30__scope_webhook_event_idempotency_by_workspace.sql
└── seeds/
    └── seed-data.sql           # Demo users, workspaces, and baseline policy records
```

---

## ⚙️ MySQL 8.4 Connection Parameters

All connections must enforce UTC session timezones and `READ-COMMITTED` isolation:

```
jdbc:mysql://<host>:<port>/<database>?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true
```

## 🚀 Running Seeds & Migrations Manually

To load the seed data into your local MySQL container:

```bash
docker exec -i aegisx-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD aegisx < database/seeds/seed-data.sql
```

