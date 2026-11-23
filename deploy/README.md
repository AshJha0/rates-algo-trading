# Deployment

Images, configuration, monitoring and the rehearsal for the simulated environment. The design is in docs/ARCHITECTURE.md (Operations); the contracts are in API_SPEC.md Sections 17.11 and 17.12; the runbooks are in docs/runbooks/.

| Path | What it is |
|---|---|
| `trading/Dockerfile`, `venue-sim/Dockerfile`, `report/Dockerfile` | One image per process on `eclipse-temurin:25-jre`, non-root, Agrona `--add-opens` flags on the launch line |
| `compose.yaml` | The simulated environment: trading (blue, and green under the `green` profile), venue simulator, Prometheus, Grafana, report job (`batch` profile) |
| `config/*.yaml`, `config/schema/process-config.schema.json` | Versioned process configuration and its schema, validated at process start and in CI |
| `prometheus/prometheus.yml`, `prometheus/alerts.yml`, `prometheus/metrics.txt` | Scrape configuration, alert rules (each with a runbook) and the metric-name catalogue |
| `grafana/` | Dashboard JSON and provisioning |
| `rehearsal.py` | Makes throwaway TLS material, starts the processes (local JVMs or containers), checks readiness over HTTPS, exercises the kill switch, checks every quote is pulled and the kill journaled, then kills the trading process hard and checks that it recovers from its journals |
| `bluegreen.sh` | Switches the active colour: target ready, kill on the old colour, quotes confirmed gone, switch recorded |
| `OWNER_CHECKLIST.md` | Repository settings only the owner can change |

Control endpoint. The configuration files bind `127.0.0.1`. Every `/control/*` request needs the `X-Control-Token` header; with no token set the control endpoints answer 403 and the process refuses to start on any address that is not loopback. The compose file sets `RATES_HTTP_BIND=0.0.0.0` inside each container, because a published port does not reach a container's loopback, and publishes the ports on the host's loopback only. `/health/*` and `/metrics` need no token. With `http.tls` the endpoints are HTTPS, TLS 1.3 only. Plain HTTP is for loopback: on any other address the process refuses to start unless it has both the token and `http.tls`, so the token never crosses a network in clear text. The committed files set `http.tls`, so the containers serve HTTPS.

Secrets. No password, token or private key is in this repository, in an image or in the compose file. A configuration file names where each one comes from: an environment variable (`...Env`) or a file (`...File`, for a Docker or Kubernetes secret mount), exactly one of the two. A key written as a value has no place to go: the schema refuses `password:` and `keyStorePassword:`. Key stores are PKCS#12 files named by path, provided at deploy time under `/run/rates/certs` (or any path the file names) and never committed. At start the process checks that every named variable is set and every named file is readable, and exits 2 with a message that names the variable or file, never a value.

| Key | Meaning | Default |
|---|---|---|
| `http.tls.keyStorePath`, `keyStorePasswordEnv` or `keyStorePasswordFile` | Certificate and key of the HTTPS endpoints. Required when `http.bindAddress` is not loopback | absent: plain HTTP, loopback only |
| `fix.resetOnLogon` | `true` restarts FIX sequence numbers at every logon; `false` resumes them after a restart | `true` |
| `fix.transport` | `tls` or `plain-loopback` | `plain-loopback` |
| `fix.tls.trustStorePath`, `trustStorePasswordEnv` or `...File` | Initiator side: the certificates it believes. Required with `transport: tls` | |
| `fix.tls.keyStorePath`, `keyStorePasswordEnv` or `...File` | Initiator's client certificate, for mutual TLS | none |
| `fix.tls.verifyHostname`, `fix.tls.handshakeTimeoutMillis` | Check that the certificate names the host dialled; time allowed for a handshake | `true`, `5000` |
| `fix.acceptor.keyStorePath`, `keyStorePasswordEnv` or `...File` | Certificate of the venue simulators' acceptors, which run inside the trading process. Required with `transport: tls` | |
| `fix.acceptor.requireClientCertificate`, `trustStorePath`, `trustStorePasswordEnv` or `...File` | Demand a client certificate and what to check it against; needs `fix.tls.keyStorePath` too | `false` |
| `fix.credentials.username`, `passwordEnv` or `passwordFile` | FIX Logon Username(553) and Password(554) | no credentials |
| `fix.reconnect.maxAttempts`, `initialBackoffMillis`, `maxBackoffMillis` | Reconnection attempts and the doubling wait between them | `5`, `100`, `2000` |

A file with no `fix` section runs the simulator preset: plain loopback, sequence numbers reset on logon. The committed trading files resume (`resetOnLogon: false`) over TLS with credentials. They name these variables: `RATES_CONTROL_TOKEN`, `RATES_HTTP_KEYSTORE_PASSWORD`, `RATES_FIX_TRUSTSTORE_PASSWORD`, `RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD`, `RATES_FIX_PASSWORD`; and these files: `/run/rates/certs/server-keystore.p12` and `/run/rates/certs/truststore.p12`. The compose file mounts the directory named by `RATES_CERTS_DIR` there, read-only, and expects `ca.pem` beside the stores for the health checks and for Prometheus, which scrapes over HTTPS.

Test material. The rehearsal makes its own each run with the `keytool` next to the `java` it starts: one self-signed certificate for `localhost` and `127.0.0.1`, valid two days, in `server-keystore.p12` (HTTP endpoints and FIX acceptors), the same certificate in `truststore.p12` (FIX initiators) and as `ca.pem` (the rehearsal's own HTTPS client, curl, Prometheus). The passwords are random (`secrets`), reach keytool through `-storepass:env` and the processes through the variables above, and never appear on a command line. Process mode writes a copy of each configuration that points at the generated files; Docker mode mounts the generated directory at `/run/rates/certs`. Everything is deleted when the rehearsal ends. To make a set by hand for `docker compose up`:

```bash
export RATES_CERTS_DIR="$PWD/deploy/out/certs" STOREPASS="$(python -c 'import secrets; print(secrets.token_urlsafe(24))')"
mkdir -p "$RATES_CERTS_DIR" && cd "$RATES_CERTS_DIR"
keytool -genkeypair -alias sim -keyalg EC -groupname secp256r1 -dname "CN=localhost" \
  -ext SAN=dns:localhost,ip:127.0.0.1 -validity 2 -keystore server-keystore.p12 -storetype PKCS12 -storepass:env STOREPASS
keytool -exportcert -rfc -alias sim -keystore server-keystore.p12 -storepass:env STOREPASS -file ca.pem
keytool -importcert -noprompt -alias sim -file ca.pem -keystore truststore.p12 -storetype PKCS12 -storepass:env STOREPASS
export RATES_HTTP_KEYSTORE_PASSWORD="$STOREPASS" RATES_FIX_TRUSTSTORE_PASSWORD="$STOREPASS" RATES_FIX_ACCEPTOR_KEYSTORE_PASSWORD="$STOREPASS"
export RATES_FIX_PASSWORD="$(python -c 'import secrets; print(secrets.token_urlsafe(24))')"
```

`deploy/out/` is where the report job already writes; keep generated stores out of version control wherever you put them. A real deployment is given stores issued by its own certificate authority instead.

Certificate rotation. A process reads its stores once, at start. To rotate: put the new stores in place under the same paths (or change the paths through a reviewed configuration change), then restart one colour at a time with `bluegreen.sh`, so quoting never depends on a process that is restarting. When the issuer changes, first ship a trust store that holds both the old and the new issuer, restart, then ship the new key stores, restart, and only then drop the old issuer. `bluegreen.sh` calls the colours with curl: give it `BLUE_URL` and `GREEN_URL` as `https://` addresses and point `CURL_CA_BUNDLE` at `ca.pem`. Watch the expiry dates (`keytool -list -v -keystore ... | grep Valid`); an expired certificate fails the FIX handshake and the health checks.

Restart after a crash. Start the process again on the same journal directory; nothing is deleted or moved first. It rebuilds its state from the journals, reports `"recovered":true` in `GET /control/status`, and, because the deployment files set `fix.resetOnLogon: false`, resumes the FIX sessions at the journaled sequence numbers instead of starting from 1. Do not treat the restart as finished until `/health/ready` answers 200. Whether a kill switch engaged before the crash is still engaged afterwards is what the journal says; check the status and readiness reason before re-enabling anything. The process-mode rehearsal runs this: it kills the trading JVM without a shutdown hook, starts it on the same journals and checks the recovered flag and readiness. The full procedure is in docs/runbooks/.

Kill and blue/green. `POST /control/kill` answers 200 only when the withdrawal was confirmed at both venues, and 503 with the reason otherwise. `bluegreen.sh` records the switch only on that 200 with zero live quotes. If the old colour is down and cannot answer, confirm at the venues that nothing of ours is working, then re-run with `RATES_OLD_COLOUR_CONFIRMED_DOWN=1`; the override is written into the record.

Loop threads. The optional `runtime` section of a configuration file sets `loopIdleStrategy` (`busy-spin`, `yield` or `backoff`) and `loopCpu` (Linux only; -1 for no pinning). Without it the process runs as before: backoff, not pinned.

Run the rehearsal locally without Docker:

```bash
mvn -B -ntp -DskipTests package -pl rates-app -am
python deploy/rehearsal.py --mode process
```

With Docker:

```bash
mvn -B -ntp -DskipTests package -pl rates-app,rates-posttrade -am
python deploy/rehearsal.py --mode docker
```

Docker mode needs a JDK or JRE on the host as well, for `keytool`; it sets the token, the passwords and `RATES_CERTS_DIR` itself.

Docker was not installed on the machine these files were written on (2026-10-31), so the Dockerfiles and the compose file were checked by review and by `docker build` in CI where the runner has Docker; the process-mode rehearsal ran locally and passed. The same holds for the TLS changes of 2026-11-23: the compose file, the Dockerfiles' health checks and the Prometheus scrape over HTTPS were checked by review and by parsing, not by running Docker.
