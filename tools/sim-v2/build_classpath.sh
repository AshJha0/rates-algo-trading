#!/usr/bin/env bash
# Builds rates-app and the modules it depends on from this checkout (compile only, nothing
# installed in ~/.m2) and writes the classpath the simulator v2 runner needs to
# rates-app/target/sim-v2-cp.txt: every module's own target/classes, then the third-party jars.
# The detached launcher copies those class directories into the run's output directory before it
# starts, so a later rebuild of the checkout cannot change a run that is in progress.
#
#   MVN=/path/to/mvn tools/sim-v2/build_classpath.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
mvn="${MVN:-mvn}"
# compile in the same invocation, so the reactor resolves sibling modules to their target/classes
"$mvn" -q -o -B -ntp -pl rates-app -am compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt   -Dspotbugs.skip=true -Djacoco.skip=true -Dcheckstyle.skip=true
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
printf '%s%s%s\n' "$cp" "$sep" "$third_party" > rates-app/target/sim-v2-cp.txt
echo "classpath written to rates-app/target/sim-v2-cp.txt"
