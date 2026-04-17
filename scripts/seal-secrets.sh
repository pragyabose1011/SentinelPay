#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# seal-secrets.sh — Encrypt secret-template.yaml into a SealedSecret.
#
# Prerequisites:
#   - kubeseal CLI: brew install kubeseal  (or see https://github.com/bitnami-labs/sealed-secrets)
#   - kubectl configured and pointing at the target cluster
#   - Sealed Secrets controller installed in kube-system (see k8s/sealed-secrets/controller.yaml)
#
# Usage:
#   ./scripts/seal-secrets.sh
#
# The output file (k8s/config/sealed-secret.yaml) is safe to commit.
# The input file (k8s/config/secret-template.yaml) must NEVER be committed
# with real values — it should always contain REPLACE_ME placeholders.
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

INPUT="$REPO_ROOT/k8s/config/secret-template.yaml"
OUTPUT="$REPO_ROOT/k8s/config/sealed-secret.yaml"
CERT="$REPO_ROOT/k8s/sealed-secrets/pub-cert.pem"

# ---------------------------------------------------------------------------
# 1. Optionally fetch the latest public cert from the live cluster.
#    Skip this step if you already have pub-cert.pem committed.
# ---------------------------------------------------------------------------
if [ ! -f "$CERT" ]; then
  echo "Fetching Sealed Secrets public certificate from cluster..."
  kubeseal \
    --fetch-cert \
    --controller-name=sealed-secrets-controller \
    --controller-namespace=kube-system \
    > "$CERT"
  echo "Certificate saved to $CERT (safe to commit)"
fi

# ---------------------------------------------------------------------------
# 2. Seal the secret template.
# ---------------------------------------------------------------------------
echo "Sealing $INPUT..."
kubeseal \
  --format yaml \
  --cert "$CERT" \
  --namespace sentinelpay \
  < "$INPUT" \
  > "$OUTPUT"

echo ""
echo "SealedSecret written to: $OUTPUT"
echo ""
echo "Next steps:"
echo "  1. Review $OUTPUT to confirm it contains only encrypted data."
echo "  2. Commit $OUTPUT and $CERT — both are safe to store in git."
echo "  3. Apply with: kubectl apply -f $OUTPUT"
echo ""
echo "To rotate secrets: update $INPUT, re-run this script, commit, and re-apply."
