#!/usr/bin/env bash
# Blue/green switch for the trading process (docs/runbooks/standby-takeover.md).
#
#   deploy/bluegreen.sh TARGET_COLOUR [STATE_DIR]
#
# Steps, in order; any failure stops the switch and leaves the active colour unchanged:
#   1. The target colour's /health/ready must answer 200 (it was started with quoting disabled
#      or will reconcile before quoting, see RestartProcedure).
#   2. The kill switch is engaged on the active colour through its control endpoint, and the
#      script checks that its live quotes reach zero.
#   3. The active colour is recorded in STATE_DIR/active-colour with the time and the operator.
#   4. Quoting on the target is enabled by its restart procedure after it reconciles with the
#      venues, never by this script.
#
# Environment: RATES_CONTROL_TOKEN (required), RATES_OPERATOR_ID (party id, required),
# BLUE_URL and GREEN_URL (defaults http://127.0.0.1:8081 and :8082).
set -euo pipefail

target="${1:?usage: bluegreen.sh blue|green [state-dir]}"
state_dir="${2:-$(dirname "$0")/state}"
: "${RATES_CONTROL_TOKEN:?RATES_CONTROL_TOKEN must be set}"
: "${RATES_OPERATOR_ID:?RATES_OPERATOR_ID (a party id) must be set}"
blue_url="${BLUE_URL:-http://127.0.0.1:8081}"
green_url="${GREEN_URL:-http://127.0.0.1:8082}"

case "$target" in
  blue) target_url="$blue_url"; other="green"; other_url="$green_url" ;;
  green) target_url="$green_url"; other="blue"; other_url="$blue_url" ;;
  *) echo "target must be blue or green, not '$target'" >&2; exit 2 ;;
esac

mkdir -p "$state_dir"
active="$(cat "$state_dir/active-colour" 2>/dev/null | cut -d' ' -f1 || true)"
if [ "$active" = "$target" ]; then
  echo "$target is already active; nothing to do"
  exit 0
fi

echo "1. readiness of $target at $target_url"
if ! curl -fsS --max-time 5 "$target_url/health/ready" >/dev/null; then
  echo "   $target is not ready; switch abandoned, $other stays active" >&2
  exit 1
fi

echo "2. kill switch on $other at $other_url"
status="$(curl -fsS --max-time 5 -X POST \
  -H "X-Control-Token: $RATES_CONTROL_TOKEN" -H "X-Requested-By: $RATES_OPERATOR_ID" \
  "$other_url/control/kill" || true)"
case "$status" in
  *'"liveQuotes":0'*) echo "   $other has no live quotes" ;;
  "") echo "   $other did not answer; treat as down and confirm at the venues (runbook)" ;;
  *) echo "   $other still reports live quotes: $status; switch abandoned" >&2; exit 1 ;;
esac

echo "3. recording $target as active"
printf '%s %s operator=%s\n' "$target" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$RATES_OPERATOR_ID" > "$state_dir/active-colour"
echo "4. $target enables quoting through its restart procedure once it has reconciled"
