#!/usr/bin/env bash
# Builds rates-strategy (and the modules it depends on) from this checkout and runs one cycle
# of the forward log (rates-strategy ForwardCli; docs/research/PAPER_TRADING_PLAN.md). Arguments are
# passed to the command line, for example
#
#   research/forward/run_forward.sh --forward-dir forward --as-of 2026-11-06 --commit "$(git rev-parse HEAD)"
#   research/forward/run_forward.sh --forward-dir forward --verify
#
# The classpath uses this checkout's own target/classes for every project module, never jars
# installed in ~/.m2 by another checkout, so the run is tied to this commit.
set -euo pipefail
cd "$(dirname "$0")/../.."
# FORWARD_MVN_FLAGS replaces the default -o (offline), for example with -B -ntp on a CI runner.
if [ "${FORWARD_SKIP_BUILD:-0}" != "1" ]; then
  mvn -q ${FORWARD_MVN_FLAGS--o} -pl rates-strategy -am install -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true -Djacoco.skip=true
  mvn -q ${FORWARD_MVN_FLAGS--o} -pl rates-strategy dependency:build-classpath -Dmdep.outputFile=target/cp.txt
fi
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";" ;; esac
third_party=$(tr "$sep" '\n' < rates-strategy/target/cp.txt | grep -v 'io[/\]ratesalgo' | paste -sd "$sep" -)
cp="rates-strategy/target/classes"
for m in rates-*/; do
  m="${m%/}"
  if [ "$m" != "rates-strategy" ] && [ -d "$m/target/classes" ]; then
    cp="${cp}${sep}${m}/target/classes"
  fi
done
cp="${cp}${sep}${third_party}"
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED -cp "$cp" io.ratesalgo.strategy.forward.ForwardCli "$@"
