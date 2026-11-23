#!/usr/bin/env bash
# Builds rates-app (and the modules it depends on) from this checkout and runs the production
# engine re-run of the P&L audit (DeskAudit.java) on one pre-registration, AUDIT-11 to AUDIT-14.
# Arguments after the first are passed to DeskAudit (for example --days for a pilot on
# fitting-period days with --ledger pointing at a scratch file).
#
#   research/audit/run_desk_audit.sh research/prereg/AUDIT-11.yaml --out research/audit/results/AUDIT-11.json
#
# Simulator v2 (full-day sessions by seed; the output directory must be outside git). That path
# builds its classpath with tools/sim-v2/build_classpath.sh (nothing installed in ~/.m2):
#
#   research/audit/run_desk_audit.sh --sim-v2 pilot --prereg research/prereg/SIM3-BASE-001.yaml --pilot 8 --out D:/runs/pilot
#
# Long runs go through tools/sim-v2/launch_detached.cmd instead, which survives the console.
#
# The classpath uses this checkout's own target/classes for every project module, never jars
# installed in ~/.m2 by another checkout, so the run is tied to this commit.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ "${1:-}" = "--sim-v2" ]; then
  if [ "${AUDIT_SKIP_BUILD:-0}" != "1" ]; then
    tools/sim-v2/build_classpath.sh
  fi
  exec java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/java.nio=ALL-UNNAMED -cp "$(cat rates-app/target/sim-v2-cp.txt)" research/audit/DeskAudit.java "$@"
fi
prereg="$1"
shift
if [ "${AUDIT_SKIP_BUILD:-0}" != "1" ]; then
  mvn -q -o -pl rates-app -am install -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true -Djacoco.skip=true
  mvn -q -o -pl rates-app dependency:build-classpath -Dmdep.outputFile=target/cp.txt
fi
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
third_party=$(tr "$sep" '\n' < rates-app/target/cp.txt | grep -v 'io[/\]ratesalgo' | paste -sd "$sep" -)
cp="rates-app/target/classes"
for m in rates-*/; do
  m="${m%/}"
  if [ "$m" != "rates-app" ] && [ -d "$m/target/classes" ]; then
    cp="${cp}${sep}${m}/target/classes"
  fi
done
cp="${cp}${sep}${third_party}"
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED -cp "$cp" research/audit/DeskAudit.java --prereg "$prereg" "$@"
