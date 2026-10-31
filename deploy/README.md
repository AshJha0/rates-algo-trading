# Deployment

Images, configuration, monitoring and the rehearsal for the simulated environment. The design is in docs/ARCHITECTURE.md (Operations); the contracts are in API_SPEC.md Sections 17.11 and 17.12; the runbooks are in docs/runbooks/.

| Path | What it is |
|---|---|
| `trading/Dockerfile`, `venue-sim/Dockerfile`, `report/Dockerfile` | One image per process on `eclipse-temurin:25-jre`, non-root, Agrona `--add-opens` flags on the launch line |
| `compose.yaml` | The simulated environment: trading (blue, and green under the `green` profile), venue simulator, Prometheus, Grafana, report job (`batch` profile) |
| `config/*.yaml`, `config/schema/process-config.schema.json` | Versioned process configuration and its schema, validated at process start and in CI |
| `prometheus/prometheus.yml`, `prometheus/alerts.yml`, `prometheus/metrics.txt` | Scrape configuration, alert rules (each with a runbook) and the metric-name catalogue |
| `grafana/` | Dashboard JSON and provisioning |
| `rehearsal.py` | Starts the processes (local JVMs or containers), checks readiness, exercises the kill switch, checks every quote is pulled and the kill journaled |
| `bluegreen.sh` | Switches the active colour: target ready, kill on the old colour, quotes confirmed gone, switch recorded |
| `OWNER_CHECKLIST.md` | Repository settings only the owner can change |

Run the rehearsal locally without Docker:

```bash
mvn -B -ntp -DskipTests package -pl rates-ops -am
python deploy/rehearsal.py --mode process
```

With Docker:

```bash
mvn -B -ntp -DskipTests package -pl rates-ops,rates-posttrade -am
export RATES_CONTROL_TOKEN="$(python -c 'import secrets; print(secrets.token_hex(16))')"
python deploy/rehearsal.py --mode docker
```

Docker was not installed on the machine these files were written on (2026-10-31), so the Dockerfiles and the compose file were checked by review and by `docker build` in CI where the runner has Docker; the process-mode rehearsal ran locally and passed.
