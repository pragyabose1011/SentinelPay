#!/usr/bin/env bash
# Generates a self-signed TLS certificate for local development.
# In production, replace certs/ with real certificates from Let's Encrypt or your CA.
set -euo pipefail

CERTS_DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$CERTS_DIR"

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout "$CERTS_DIR/server.key" \
  -out    "$CERTS_DIR/server.crt" \
  -subj   "/C=IN/ST=Karnataka/L=Bangalore/O=SentinelPay/CN=localhost"

echo "Self-signed certificate generated in $CERTS_DIR"
echo "WARNING: This certificate is for local development only."
