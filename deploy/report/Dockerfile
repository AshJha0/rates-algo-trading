# End-of-day report generator image: a batch job, no ports. Build from the repository root:
#   mvn -B -ntp -DskipTests package -pl rates-posttrade -am
#   docker build -f deploy/report/Dockerfile -t rates-report:dev .
# Run with the journal, reference files and output directory mounted, for example:
#   docker run --rm -v rates-journal:/var/lib/rates -v "$PWD/out:/out" rates-report:dev \
#     --journal /var/lib/rates/journal/trading-blue.raj --date 2026-10-30 \
#     --instruments /opt/rates/reference/instruments.csv --books /opt/rates/reference/books.csv \
#     --marks /opt/rates/reference/marks.csv --carry /opt/rates/reference/carry.csv --out /out
FROM eclipse-temurin:25-jre

RUN groupadd --system rates \
 && useradd --system --gid rates --home-dir /opt/rates --shell /usr/sbin/nologin rates

WORKDIR /opt/rates
COPY rates-posttrade/target/lib/ lib/
COPY rates-posttrade/target/rates-posttrade-*.jar lib/
COPY rates-posttrade/src/test/resources/fixture/ reference/

USER rates

ENTRYPOINT ["java", \
  "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "-cp", "/opt/rates/lib/*", \
  "io.ratesalgo.posttrade.report.EodReportMain"]
