#!/usr/bin/env bash
# Builds rates-backtest from this checkout and runs the P&L audit harness on one
# pre-registration. Arguments after the first are passed to PnlAudit (for example --days for a
# pilot on fitting-period days with --ledger pointing at a scratch file).
#
#   research/audit/run_audit.sh research/prereg/AUDIT-01.yaml --out research/audit/results/AUDIT-01.json
#
# The classpath uses this checkout's own target/classes for the project modules, never jars
# installed in ~/.m2 by another checkout, so the run is tied to this commit.
set -euo pipefail
cd "$(dirname "$0")/../.."
prereg="$1"
shift
if [ "${AUDIT_SKIP_BUILD:-0}" != "1" ]; then
  mvn -q -o -pl rates-backtest -am install -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true -Djacoco.skip=true
  mvn -q -o -pl rates-backtest dependency:build-classpath -Dmdep.outputFile=target/cp.txt
fi
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
third_party=$(tr "$sep" '\n' < rates-backtest/target/cp.txt | grep -v 'io[/\\]ratesalgo' | paste -sd "$sep" -)
cp="rates-backtest/target/classes${sep}rates-common/target/classes${sep}rates-messages/target/classes"
cp="${cp}${sep}rates-journal/target/classes${sep}${third_party}"
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED -cp "$cp" research/audit/PnlAudit.java --prereg "$prereg" "$@"
