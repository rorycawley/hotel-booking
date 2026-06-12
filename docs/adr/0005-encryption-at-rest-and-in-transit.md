# ADR 0005 — Encryption at rest and in transit

## Context

A government-grade registry MUST guarantee that:

1. Data on disk cannot be read by anyone who steals a backup, snapshot,
   or storage volume.
2. Data in motion cannot be read by anyone with network access between
   the application, Postgres, RabbitMQ, and the REST clients.
3. PII at the FIELD level survives even a database administrator who
   can `SELECT *` from the events table.

(1) and (2) are infrastructure concerns; (3) is an application
concern and is handled separately by ADR-0006 (crypto-shredding) and
the `subject-keys` brick.

This ADR records the deployment-side requirements for (1) and (2),
plus the application-side configuration knobs that support them.

## Decision

### In transit — application configuration

- **Postgres**: the JDBC URL MUST include `sslmode=verify-full` (or at
  minimum `sslmode=require`) for production. The `event-store` brick's
  `(postgres jdbc-url)` constructor accepts any standard JDBC URL, so
  callers pass:
  ```
  jdbc:postgresql://host:5432/booking?sslmode=verify-full&sslrootcert=/etc/ssl/postgres-ca.crt
  ```
- **RabbitMQ**: the `integration` brick's `(rabbitmq {:uri ...})`
  constructor accepts an AMQP URI. Production uses `amqps://` and a
  CA bundle:
  ```
  amqps://user:pw@host:5671/vhost
  ```
  The HikariCP and Langohr defaults already support TLS through these URIs.

- **REST**: the rest-api base does not terminate TLS itself; deploy
  behind an ingress (nginx, Traefik, ALB, GCP LB) that does. mTLS
  for service-to-service is a separate deployment concern; the app
  reads `X-Actor-Id` from a trusted reverse proxy that has already
  done the certificate work.

### At rest — infrastructure choices (no application change)

Pick ONE of the following per environment; the application is
indifferent to which:

| Layer | Mechanism | Notes |
|---|---|---|
| **Disk** | LUKS (self-managed Linux) | Whole-volume; survives device theft only |
| **Storage** | AWS EBS encryption, GCP CMEK on Persistent Disks, Azure Disk Encryption | Free; supports customer-managed keys |
| **Postgres** | RDS / Aurora / Cloud SQL "encryption at rest" toggle | Use customer-managed KMS keys (CMK) so rotation is in YOUR control |
| **Postgres self-hosted** | `pgcrypto` for column-level, or filesystem-level (LUKS) | TDE-style column encryption is heavy; prefer filesystem layer |
| **Backups** | `pg_basebackup` to a KMS-encrypted bucket (S3 SSE-KMS, GCS CMEK) | Backups inherit the same risk surface as primary data |
| **RabbitMQ** | EBS encryption on the broker volume | Mnesia files at rest |

### What encryption-at-rest does NOT cover

- A live application connecting with the correct credentials and
  `SELECT`-ing the events table: **the database decrypts for it**.
  That is the threat ADR-0006 (crypto-shredding) addresses by
  encrypting PII fields with subject-scoped keys at the application
  layer.
- A backup taken with credentials and saved to an unencrypted bucket:
  the encryption helped while at rest in the source but is lost on
  copy. Backup destinations MUST also be encrypted.

### Configuration knobs in this codebase

The application is encryption-agnostic. The only code surface is:

- `event-store/postgres` accepts any JDBC URL, so `sslmode` and `sslcert`
  parameters pass straight through to the driver.
- `integration/rabbitmq` accepts any AMQP URI, so `amqps://` works.
- `system/prod-system` does NOT enforce TLS - that's a deployment
  policy, not an application policy. A deployment that forgets to
  set `sslmode=verify-full` is a deployment defect; the application
  cannot fix it for you.

### Verifying a deployment

Smoke checks an operator runs once per environment:

```bash
# Postgres requires TLS
psql "$JDBC_URL" -c "SHOW ssl;"     # -> on

# RabbitMQ over AMQPS
openssl s_client -connect rabbit:5671 < /dev/null | grep "Verify return code"

# At-rest verified by the storage provider's console
# (RDS encryption: "encryption enabled" + the KMS key id you specified)
```

## Consequences

- The application does not enforce TLS; misconfigured deployments will
  silently fall back to plaintext. **Verify with the smoke checks
  above as part of the deployment runbook.**
- ADR-0006 (crypto-shredding) layers on top of this: field-level
  encryption with destroyable per-subject keys defends against a
  database administrator who has both the database credentials AND
  legitimate access to backups. Encryption at rest alone does not.

## Status

Accepted. This is the BASELINE for production deployments; #16
(crypto-shredding, ADR-0006) is the layer above it that closes the
DBA-as-threat-actor surface and the GDPR Article 17 surface.
