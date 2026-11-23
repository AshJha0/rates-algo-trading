# Trading process image: rates-app's TradingProcessMain with the D2C and CLOB simulators in
# process (API_SPEC.md Section 18.1). Build from the repository root after packaging the jars:
#   mvn -B -ntp -DskipTests package -pl rates-app -am
#   docker build -f deploy/trading/Dockerfile -t rates-trading:dev .
# The process validates its configuration at start and exits 2 if it is invalid. The reference
# data fixtures are copied because the simulators and the pricing binding load them at start.
# The image holds no key store and no password. The stores are mounted at /run/rates/certs when
# the container starts and the passwords arrive in the environment (deploy/compose.yaml). The
# health check uses HTTPS when the mounted directory has ca.pem, and plain HTTP on loopback otherwise.
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system rates \
 && useradd --system --gid rates --home-dir /opt/rates --shell /usr/sbin/nologin rates \
 && mkdir -p /var/lib/rates/journal /var/lib/rates/log \
 && chown -R rates:rates /var/lib/rates

WORKDIR /opt/rates
COPY rates-app/target/lib/ lib/
COPY rates-app/target/rates-app-*.jar lib/
COPY deploy/config/ config/
COPY data/refdata/ data/refdata/

USER rates
EXPOSE 8081
VOLUME ["/var/lib/rates"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD if [ -f /run/rates/certs/ca.pem ]; then \
        curl -fsS --cacert /run/rates/certs/ca.pem https://127.0.0.1:${RATES_HTTP_PORT:-8081}/health/ready; \
      else \
        curl -fsS http://127.0.0.1:${RATES_HTTP_PORT:-8081}/health/ready; \
      fi || exit 1

# JVM flags, one by one (DECISIONS.md DR-R6-L06; the wired-desk benchmark runs with exactly these,
# WiredDeskSuite.shippedFlags, and a test keeps the two lists equal):
# - the three --add-opens: Agrona 2.x needs them on every JVM that runs the platform
#   (docs/ARCHITECTURE.md);
# - --enable-native-access=ALL-UNNAMED: core pinning (sched_setaffinity) and segment locking (mlock)
#   call the C library through the foreign function API; without the flag the JDK warns once;
# - -XX:+UseZGC: the loop allocates nothing in steady state, but the in-process venue simulators,
#   TLS (about 1.3 KB per record inside the JDK's engine) and the HTTP endpoints do. ZGC collects
#   that concurrently; its pauses are sub-millisecond and do not grow with the heap. The Parallel
#   collector stops every thread, the loop's included, for each young collection
#   (docs/BENCHMARKS_V2.md has both measured on the wired desk);
# - -Xms512m -Xmx512m: a fixed heap, so it is never resized under load;
# - -XX:+AlwaysPreTouch: every heap page is faulted in at start, not on first use by a thread that
#   is quoting;
# - -XX:+UseTransparentHugePages: the heap in 2 MB pages where the host's THP mode is madvise or
#   always, fewer TLB misses; ignored where THP is off;
# - -Xlog gc and safepoint to a rotated file on the volume: every pause and time-to-safepoint is on
#   record for the incident review, at a few hundred bytes per event.
# No AOT cache or CDS archive yet: building one needs a training run of the process in the image
# build, which this file cannot do reproducibly today (the process has no bounded run mode).
ENTRYPOINT ["java", \
  "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "--enable-native-access=ALL-UNNAMED", \
  "-XX:+UseZGC", "-Xms512m", "-Xmx512m", "-XX:+AlwaysPreTouch", "-XX:+UseTransparentHugePages", \
  "-Xlog:gc*,safepoint:file=/var/lib/rates/log/jvm.log:uptime,level,tags:filecount=5,filesize=20m", \
  "-cp", "/opt/rates/lib/*", \
  "io.ratesalgo.app.TradingProcessMain"]
# For the low-latency profile pass --config /opt/rates/config/trading-lowlatency.yaml and run the
# container on reserved cores (docker run --cpuset-cpus=...; the file says which it expects).
CMD ["--config", "/opt/rates/config/trading-blue.yaml", \
     "--schema", "/opt/rates/config/schema/process-config.schema.json"]
