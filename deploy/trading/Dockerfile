# Trading process image: rates-app's TradingProcessMain with the D2C and CLOB simulators in
# process (API_SPEC.md Section 18.1). Build from the repository root after packaging the jars:
#   mvn -B -ntp -DskipTests package -pl rates-app -am
#   docker build -f deploy/trading/Dockerfile -t rates-trading:dev .
# The process validates its configuration at start and exits 2 if it is invalid. The reference
# data fixtures are copied because the simulators and the pricing binding load them at start.
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system rates \
 && useradd --system --gid rates --home-dir /opt/rates --shell /usr/sbin/nologin rates \
 && mkdir -p /var/lib/rates/journal \
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
  CMD curl -fsS http://127.0.0.1:${RATES_HTTP_PORT:-8081}/health/ready || exit 1

# Agrona 2.x needs these opens on every JVM that runs the platform (docs/ARCHITECTURE.md).
ENTRYPOINT ["java", \
  "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "-XX:+UseZGC", "-XX:+AlwaysPreTouch", "-Xms512m", "-Xmx512m", \
  "-cp", "/opt/rates/lib/*", \
  "io.ratesalgo.app.TradingProcessMain"]
CMD ["--config", "/opt/rates/config/trading-blue.yaml", \
     "--schema", "/opt/rates/config/schema/process-config.schema.json"]
