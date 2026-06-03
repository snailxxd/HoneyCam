#!/usr/bin/env bash
set -euo pipefail

# Capture HTTP/RTSP traffic for honeypot experiments.
# Usage:
#   ./scripts/start_capture.sh eth0
#   ./scripts/start_capture.sh eth0 /var/log/honeycam

IFACE="${1:-eth0}"
OUT_DIR="${2:-pcap}"
TS="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${OUT_DIR}/honeycam-${TS}.pcap"

mkdir -p "${OUT_DIR}"

echo "Starting tcpdump on interface ${IFACE}"
echo "Writing pcap to ${OUT_FILE}"
echo "Press Ctrl+C to stop."

sudo tcpdump -i "${IFACE}" -s 0 -w "${OUT_FILE}" "tcp port 80 or tcp port 554"
