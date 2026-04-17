#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# bootstrap.sh — One-time cluster setup: install operators, then deploy the
# full SentinelPay stack.
#
# Run this once against a fresh cluster. Subsequent deployments go through
# GitHub Actions CD (see .github/workflows/cd.yml).
#
# Prerequisites:
#   - kubectl configured and pointing at the target cluster
#   - helm 3.x installed
#   - kubeseal installed (for secret sealing step)
#
# Usage:
#   ./scripts/bootstrap.sh
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

log() { echo "[bootstrap] $*"; }
die() { echo "[bootstrap] ERROR: $*" >&2; exit 1; }

command -v kubectl &>/dev/null || die "kubectl not found"
command -v helm &>/dev/null    || die "helm not found"

# ---------------------------------------------------------------------------
# 1. Create namespace + resource quotas first (everything else goes here)
# ---------------------------------------------------------------------------
log "Creating namespace and resource quotas..."
kubectl apply -f "$REPO_ROOT/k8s/namespace.yaml"
kubectl apply -f "$REPO_ROOT/k8s/resource-quota.yaml"

# ---------------------------------------------------------------------------
# 2. cert-manager — required by the Let's Encrypt ClusterIssuer in ingress.yaml
# ---------------------------------------------------------------------------
log "Installing cert-manager..."
helm repo add jetstack https://charts.jetstack.io --force-update
helm repo update
helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager \
  --create-namespace \
  --version v1.15.0 \
  --set installCRDs=true \
  --wait

# ---------------------------------------------------------------------------
# 3. ingress-nginx — required for the Ingress resources
# ---------------------------------------------------------------------------
log "Installing ingress-nginx..."
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx --force-update
helm repo update
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.replicaCount=2 \
  --wait

# ---------------------------------------------------------------------------
# 4. Sealed Secrets controller
# ---------------------------------------------------------------------------
log "Installing Sealed Secrets controller..."
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets --force-update
helm repo update
helm upgrade --install sealed-secrets sealed-secrets/sealed-secrets \
  --namespace kube-system \
  --set fullnameOverride=sealed-secrets-controller \
  --wait

# ---------------------------------------------------------------------------
# 5. CloudNativePG operator — manages the Postgres Cluster CR
# ---------------------------------------------------------------------------
log "Installing CloudNativePG operator..."
helm repo add cnpg https://cloudnative-pg.github.io/charts --force-update
helm repo update
helm upgrade --install cnpg cnpg/cloudnative-pg \
  --namespace cnpg-system \
  --create-namespace \
  --wait

# ---------------------------------------------------------------------------
# 6. Strimzi Kafka operator — manages Kafka and KafkaTopic CRs
# ---------------------------------------------------------------------------
log "Installing Strimzi operator..."
helm repo add strimzi https://strimzi.io/charts --force-update
helm repo update
helm upgrade --install strimzi strimzi/strimzi-kafka-operator \
  --namespace sentinelpay \
  --set watchNamespaces="{sentinelpay}" \
  --wait

# ---------------------------------------------------------------------------
# 7. Seal secrets (requires kubeseal + running Sealed Secrets controller)
# ---------------------------------------------------------------------------
if command -v kubeseal &>/dev/null; then
  log "Sealing secrets..."
  "$SCRIPT_DIR/seal-secrets.sh"
else
  log "WARNING: kubeseal not found — skipping secret sealing."
  log "         Edit k8s/config/secret-template.yaml with real values,"
  log "         then run: kubectl apply -f k8s/config/secret-template.yaml"
  log "         (do NOT commit the file with real values)"
fi

# ---------------------------------------------------------------------------
# 8. Apply the full application stack
# ---------------------------------------------------------------------------
log "Applying ConfigMap and SealedSecret..."
kubectl apply -f "$REPO_ROOT/k8s/config/configmap.yaml"

if [ -f "$REPO_ROOT/k8s/config/sealed-secret.yaml" ]; then
  kubectl apply -f "$REPO_ROOT/k8s/config/sealed-secret.yaml"
else
  log "WARNING: No sealed-secret.yaml found — applying secret template directly."
  log "         Only do this in a private cluster; never commit real secret values."
  kubectl apply -f "$REPO_ROOT/k8s/config/secret-template.yaml"
fi

log "Applying infrastructure (Postgres, Redis, Kafka)..."
kubectl apply -f "$REPO_ROOT/k8s/postgres/cluster.yaml"
kubectl apply -f "$REPO_ROOT/k8s/redis/statefulset.yaml"
kubectl apply -f "$REPO_ROOT/k8s/redis/service.yaml"
kubectl apply -f "$REPO_ROOT/k8s/kafka/kafka.yaml"

log "Waiting for Kafka cluster to be ready (this takes ~2 minutes)..."
kubectl wait kafka/sentinelpay-kafka \
  --for=condition=Ready \
  --timeout=300s \
  -n sentinelpay || log "WARNING: Kafka not ready yet — topics will be created on retry"

log "Applying Kafka topics..."
kubectl apply -f "$REPO_ROOT/k8s/kafka/topics.yaml"

log "Applying application services..."
kubectl apply -f "$REPO_ROOT/k8s/payment-service/"
kubectl apply -f "$REPO_ROOT/k8s/notification-service/"
kubectl apply -f "$REPO_ROOT/k8s/kyc-service/"
kubectl apply -f "$REPO_ROOT/k8s/api-gateway/"

log "Applying observability stack..."
kubectl apply -f "$REPO_ROOT/k8s/observability/"

log "Applying ingress and network policies..."
kubectl apply -f "$REPO_ROOT/k8s/ingress.yaml"
kubectl apply -f "$REPO_ROOT/k8s/network-policies.yaml"

log ""
log "Bootstrap complete. Check pod status with:"
log "  kubectl get pods -n sentinelpay"
log ""
log "For ongoing deployments, push to main — GitHub Actions CD will handle it."
