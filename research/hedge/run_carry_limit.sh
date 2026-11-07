#!/usr/bin/env bash
# Builds rates-strategy from this checkout and runs the carry-limit experiment V1C-001
# (rates-strategy CarryLimitExperiment), for example
#
#   research/hedge/run_carry_limit.sh --prereg data/preregs/V1C-001.yaml --scope fitting
#   research/hedge/run_carry_limit.sh --prereg data/preregs/V1C-001.yaml --commit "$(git rev-parse HEAD)"
#
# The classpath uses this checkout's own target/classes for every project module.
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ "${HEDGE_SKIP_BUILD:-0}" != "1" ]; then
  mvn -q -o -pl rates-strategy -am install -DskipTests -Dspotbugs.skip=true -Dcheckstyle.skip=true \
    -Dspotless.check.skip=true -Djacoco.skip=true
  mvn -q -o -pl rates-strategy dependency:build-classpath -Dmdep.outputFile=target/cp.txt
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
  --add-opens java.base/java.nio=ALL-UNNAMED -cp "$cp" io.ratesalgo.strategy.experiment.CarryLimitExperiment "$@"
