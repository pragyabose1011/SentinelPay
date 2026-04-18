# SentinelPay

A production-grade payment platform built with Spring Boot microservices, Kafka event streaming, and Kubernetes-native infrastructure.

---

## Architecture

```
                        ┌─────────────────────────────────────────┐
                        │              api-gateway :8443           │
                        │    JWT auth · rate limiting · TLS/H2     │
                        └───────────┬──────────┬──────────┬────────┘
                                    │          │          │
                    ┌───────────────┘   ┌──────┘    ┌────┘
                    ▼                   ▼            ▼
         ┌──────────────────┐  ┌──────────────┐  ┌──────────────┐
         │  payment-service │  │  kyc-service │  │ notification │
         │      :8081       │  │    :8083     │  │  service     │
         │                  │  │              │  │    :8082     │
         │  payments        │  │  KYC submit  │  │  email +     │
         │  wallets         │  │  review      │  │  Kafka sub   │
         │  auth            │  │  status      │  └──────┬───────┘
         │  deposits        │  └──────┬───────┘         │
         │  webhooks        │         │                  │
         │  Razorpay        │         └────┐    ┌────────┘
         └──────┬─────┬─────┘              │    │
                │     │               ┌────▼────▼────┐
         ┌──────┘     └──────┐        │    Kafka     │
         ▼                   ▼        │  (Strimzi)   │
   ┌──────────┐       ┌─────────────┐ └──────────────┘
   │ Postgres │       │    Redis    │
   │ (CNPG)  │       │  Sentinel   │
   └──────────┘       └─────────────┘
```

### Services

| Service | Port | Responsibility |
|---|---|---|
| **api-gateway** | 8443 | TLS termination, JWT validation, routing, CORS |
| **payment-service** | 8081 | Payments, wallets, auth, deposits/withdrawals, webhooks |
| **kyc-service** | 8083 | KYC submission, admin review, status tracking |
| **notification-service** | 8082 | Email dispatch, Kafka event consumption |

### Infrastructure

| Component | Implementation | Notes |
|---|---|---|
| Database | CloudNativePG (Postgres 16) | 3-node streaming replication, automatic failover |
| Cache / Locks | Redis 7 + Sentinel | 1 master + 2 replicas, 3 sentinels, quorum=2 |
| Messaging | Strimzi Kafka | 3 brokers, RF=3, min-ISR=2, DLT per topic |
| Tracing | Jaeger (OTLP) | Elasticsearch persistent storage |
| Metrics | Prometheus + Grafana | Pre-built ops dashboard |
| Logs | Loki SimpleScalable + Promtail | 2 write + 2 read replicas, MinIO S3 storage, 30-day retention |
| Ingress | ingress-nginx + cert-manager | Let's Encrypt TLS, HSTS |
| Secrets | Sealed Secrets | Encrypted in git, decrypted in-cluster |

---

## Features

**Payments**
- Peer-to-peer transfers with idempotency (Redis deduplication key)
- Multi-currency with exchange rate support
- Fraud scoring on every transaction (velocity, amount, cross-border checks)
- Razorpay gateway integration (deposit flow, HMAC-SHA256 webhook verification)
- Transactional outbox pattern — events published reliably even on partial failures

**Auth & Security**
- JWT access tokens (15 min) + refresh tokens (7 days)
- Brute-force protection — sliding failure window + account lock via Redis
- Role-based access control (USER / ADMIN)
- Webhook registration with HMAC signing for downstream consumers
- Network policies: default-deny, explicit allow per service

**KYC**
- Document upload and submission flow
- Admin review queue with approve/reject
- Status published to Kafka on state change

**Observability**
- Structured JSON logs with `traceId`/`spanId` in every line
- OTLP traces to Jaeger from all 4 services
- Custom Prometheus metrics: `sentinelpay_payments_total`, `sentinelpay_payment_duration_seconds`, `sentinelpay_rate_limit_exceeded_total`, `sentinelpay_outbox_parked`
- Grafana dashboard with service health, throughput, latency percentiles (p50/p95/p99), JVM stats, and live log panel

---

## Getting Started (Local)

**Prerequisites:** Docker Desktop with Compose V2

```bash
# Clone
git clone https://github.com/pragyabose1011/SentinelPay.git
cd SentinelPay

# Copy and fill in secrets
cp .env.example .env
# Edit .env — the defaults work for local dev (Mailhog, no Razorpay)

# Start everything
docker compose up -d

# Watch logs
docker compose logs -f payment-service
```

**Local service URLs:**

| Service | URL |
|---|---|
| API Gateway (HTTPS) | https://localhost:8443 |
| payment-service (direct) | http://localhost:8081 |
| Grafana | http://localhost:3000 (admin / admin) |
| Prometheus | http://localhost:9090 |
| Mailhog (catch-all SMTP) | http://localhost:8025 |
| Jaeger UI | http://localhost:16686 |
| Kafka UI | http://localhost:8080 |

> The gateway uses a self-signed certificate in dev. Accept the browser warning or pass `-k` to curl.

### Quick API walkthrough

```bash
BASE=https://localhost:8443

# Register
curl -k -X POST $BASE/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"Secret123!","fullName":"Alice"}'

# Login
TOKEN=$(curl -k -s -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"Secret123!"}' \
  | jq -r '.accessToken')

# Check wallet
curl -k -H "Authorization: Bearer $TOKEN" $BASE/api/v1/wallets

# Send payment
curl -k -X POST $BASE/api/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"receiverEmail":"bob@example.com","amount":50.00,"currency":"INR","description":"Lunch"}'
```

---

## Deploying to Kubernetes

### First-time bootstrap

```bash
# Requires: kubectl, helm 3, kubeseal
./scripts/bootstrap.sh
```

This installs cert-manager, ingress-nginx, Sealed Secrets, CloudNativePG, and Strimzi in the correct order, then applies the full manifest stack.

### Sealing secrets before deploying

```bash
# Edit placeholder values in k8s/config/secret-template.yaml
# Then seal them (safe to commit after):
./scripts/seal-secrets.sh
```

### Ongoing deployments

Push to `main` — GitHub Actions handles the rest:

1. **CI** — builds, runs 135+ unit/slice tests, runs integration tests against real Postgres + Redis + Kafka service containers, runs OWASP dependency scan
2. **CD** — builds layered Docker images, pushes to GHCR, waits for manual approval in the `production` environment, rolls out with `kubectl set image` + rollout status check

```
main push → CI (test + scan) → manual approval gate → CD (build + push + deploy)
```

### Apply incrementally

```bash
# Apply everything at once
kubectl apply -k k8s/

# Or apply a single service
kubectl apply -f k8s/payment-service/
```

---

## Project Structure

```
SentinelPay/
├── api-gateway/                  # Spring Cloud Gateway
├── payment-service/              # Core payments, auth, wallets
├── kyc-service/                  # KYC workflow
├── notification-service/         # Email + Kafka consumer
├── docker/                       # Dev config for Grafana, Prometheus, Loki, Nginx
├── docker-compose.yml            # Full local stack
├── k8s/
│   ├── config/                   # ConfigMap + secret template
│   ├── postgres/                 # CloudNativePG Cluster CR
│   ├── redis/                    # HA StatefulSet + Sentinel
│   ├── kafka/                    # Strimzi Kafka + KafkaTopic CRs
│   ├── {service}/                # Deployment, Service, HPA, PDB per service
│   ├── observability/            # Prometheus, Grafana, Loki, Promtail, Jaeger, ES
│   ├── sealed-secrets/           # Sealed Secrets controller notes
│   ├── network-policies.yaml     # Default-deny + explicit allow rules
│   ├── resource-quota.yaml       # Namespace ResourceQuota + LimitRange
│   └── kustomization.yaml        # Single-command apply
├── scripts/
│   ├── bootstrap.sh              # One-time cluster setup
│   └── seal-secrets.sh           # Encrypt secrets with kubeseal
└── .github/workflows/
    ├── ci.yml                    # Build, test, OWASP scan, integration tests
    └── cd.yml                    # Build images, push to GHCR, deploy
```

---

## Configuration

All non-sensitive config lives in `k8s/config/configmap.yaml`. Sensitive values go in `k8s/config/secret-template.yaml`, sealed before committing.

Key environment variables:

| Variable | Default | Description |
|---|---|---|
| `RAZORPAY_ENABLED` | `false` | Set `true` in prod with real Razorpay keys |
| `AUTH_MAX_FAILED_ATTEMPTS` | `5` | Failed logins before account lock |
| `AUTH_LOCK_DURATION_MINUTES` | `30` | How long the lock lasts |
| `JWT_EXPIRATION_MS` | `900000` | Access token TTL (15 min) |
| `JWT_REFRESH_EXPIRATION_MS` | `604800000` | Refresh token TTL (7 days) |
| `FRONTEND_BASE_URL` | `https://app.sentinelpay.com` | Used in password-reset email links |

---

## Testing

```bash
# Unit + slice tests (all modules)
mvn verify

# Integration tests only (payment-service, requires Docker)
mvn verify -pl payment-service -Dgroups=integration

# Single service
mvn test -pl kyc-service
```

Test coverage: 135+ tests across unit, controller slice (@WebMvcTest), and integration (Testcontainers).
