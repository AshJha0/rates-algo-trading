#!/usr/bin/env bash
# Builds rates-strategy (and the modules it depends on) from this checkout and runs the hedge
# execution experiments V1H-001 and V1H-002 (rates-strategy HedgeExperimentCli). Arguments are
# passed to the command line, for example
#
#   research/hedge/run_hedge_experiment.sh --preregs data/preregs/V1H-001.yaml,data/preregs/V1H-002.yaml --commit "$(git rev-parse HEAD)"
#   research/hedge/run_hedge_experiment.sh --preregs data/preregs/V1H-001.yaml --scope fitting --config FILE
#
# The classpath uses this checkout's own target/classes for every project module, never jars
# installed in ~/.m2 by another checkout, so the run is tied to this commit.
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
  --add-opens java.base/java.nio=ALL-UNNAMED -cp "$cp" io.ratesalgo.strategy.experiment.HedgeExperimentCli "$@"
