# Venue simulator image (role venue-sim). The D2C and CLOB simulators run inside the trading
# process (deploy/trading/Dockerfile), so this process serves health and metrics only; it stays
# as the slot a separately deployed simulator takes. Build from the repository root after
# packaging the jars:
#   mvn -B -ntp -DskipTests package -pl rates-app -am
#   docker build -f deploy/venue-sim/Dockerfile -t rates-venue-sim:dev .
# The process validates its configuration at start and exits 2 if it is invalid.
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system rates \
 && useradd --system --gid rates --home-dir /opt/rates --shell /usr/sbin/nologin rates \
 && mkdir -p /var/lib/rates/venue-journal \
 && chown -R rates:rates /var/lib/rates

WORKDIR /opt/rates
COPY rates-app/target/lib/ lib/
COPY rates-app/target/rates-app-*.jar lib/
COPY deploy/config/ config/

USER rates
EXPOSE 8090
VOLUME ["/var/lib/rates"]

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${RATES_HTTP_PORT:-8090}/health/ready || exit 1

# Agrona 2.x needs these opens on every JVM that runs the platform (docs/ARCHITECTURE.md).
ENTRYPOINT ["java", \
  "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "-XX:+UseZGC", "-XX:+AlwaysPreTouch", "-Xms256m", "-Xmx256m", \
  "-cp", "/opt/rates/lib/*", \
  "io.ratesalgo.app.TradingProcessMain"]
CMD ["--config", "/opt/rates/config/venue-sim.yaml", \
     "--schema", "/opt/rates/config/schema/process-config.schema.json"]
