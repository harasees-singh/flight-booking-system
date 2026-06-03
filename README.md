# Flight Booking System

A Spring Boot service that models a single-airline flight booking system with a high-QPS
search path, a two-step booking flow (synchronous seat-block + asynchronous payment callback),
cancellation with partial refunds, and full observability.

- **Search** direct + connecting journeys (up to 3 legs) over an in-memory flight graph (BFS).
- **Book** a journey: atomically block seats → `PENDING_PAYMENT` → confirm/deny over Kafka.
- **Cancel** a confirmed booking: release seats → partial refund event to a black-box processor.
- **Observe** via Actuator + Prometheus + Grafana (latency, HTTP rates, Kafka lag, search & booking metrics).

See [`DESIGN.md`](DESIGN.md) for the full architecture, data model, and design decisions.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21+** | Project targets Java 21 (`pom.xml`). |
| Docker + Docker Compose | recent | For Percona, Kafka, Prometheus, Grafana. |
| Maven | not required | Use the bundled wrapper `./mvnw`. |

> Optional for the traffic script: `curl` and `jq`.

---

## Quick start

There are two ways to run the app.

### Option A — `local` profile (no infrastructure needed)

Uses in-memory **H2** and **disables Kafka** (booking/refund events are logged instead). Best for
a quick spin-up or development.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The app starts on **http://localhost:8080** and seeds ~2500 synthetic flights on first boot.

### Option B — full stack (Percona + Kafka + monitoring)

1. Start the infrastructure:

   ```bash
   docker compose up -d            # percona, kafka, prometheus, grafana
   docker compose ps               # wait until percona is healthy
   ```

2. Run the app against it (default profile):

   ```bash
   ./mvnw spring-boot:run
   ```

| Service | URL / Port | Credentials |
|---|---|---|
| Application | http://localhost:8080 | — |
| Percona (MySQL) | `localhost:3306` | `flight` / `flight` (db `flightbooking`) |
| Kafka | `localhost:9092` | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `admin` / `admin` |

Tear down infra (keep data): `docker compose down` — or `docker compose down -v` to wipe volumes.

---

## Build & package

```bash
./mvnw clean package                       # compile + run tests + build jar
java -jar target/flightbookingsystem-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

---

## Running tests

The full suite (unit + integration + an embedded-Kafka end-to-end test) needs **no external
infrastructure** — integration tests use H2 and an in-JVM Kafka broker.

```bash
./mvnw test                                # run everything
./mvnw -Dtest=BookingFlowIntegrationTest test          # a single class
./mvnw -Dtest='BookingFlowIntegrationTest#concurrentBookings_neverOversellSeats' test  # a single test
```

What's covered:
- **Unit** — services, state machine, refund policy, seat service, sweeper, Kafka consumer/stub, metrics, exception handler.
- **Integration** (`@SpringBootTest`, random port, H2) — search → book → confirm → cancel → refund, payment denial, expiry/timeout, sweeper boundaries, race-safety, no-oversell under parallel load, multi-leg journeys.
- **End-to-end Kafka** (`@EmbeddedKafka`) — payment callback consumer + refund processor over a real in-JVM broker.

---

## API reference

Base URL: `http://localhost:8080`

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/flights/search?src=&dst=&date=&pax=` | search journeys |
| `POST` | `/api/v1/bookings` | create booking (blocks seats → `PENDING_PAYMENT`) |
| `GET` | `/api/v1/bookings/{id}` | booking details |
| `POST` | `/api/v1/bookings/{id}/cancel` | cancel + raise refund |
| `GET` | `/api/v1/aircrafts`, `/api/v1/flights` | admin / seed lookups |
| `POST` | `/api/v1/_sim/payment-callback?bookingId=&success=` | **test only** — simulate the payment system (Kafka-gated) |

### Example: search → book → pay → cancel

```bash
# 1. Search (use a date within the seeded 14-day window)
curl "http://localhost:8080/api/v1/flights/search?src=DEL&dst=BLR&date=$(date +%Y-%m-%d)&pax=2"

# 2. Create a booking (use real flightIds from the search result)
curl -X POST http://localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightIds":[123],"passengers":[{"name":"Asha","age":30},{"name":"Ravi","age":28}]}'

# 3. Confirm payment (full stack only — publishes to payment.callback)
curl -X POST "http://localhost:8080/api/v1/_sim/payment-callback?bookingId=1&success=true"

# 4. Cancel (refund is raised and processed asynchronously)
curl -X POST http://localhost:8080/api/v1/bookings/1/cancel
```

---

## Observability

With the full stack running:

- Raw metrics: http://localhost:8080/actuator/prometheus
- Health: http://localhost:8080/actuator/health
- Prometheus: http://localhost:9090 (target `flightbookingsystem` should be `up`)
- Grafana: http://localhost:3000 → dashboard **flightbookingsystem-overview** (auto-provisioned)

### Generate load for the dashboards

A helper script drives searches and bookings (with a mix of confirmations, denials, expiries,
and intentional search misses):

```bash
./scripts/generate-traffic.sh
SEARCHES=200 BOOKINGS=60 MISS_PCT=30 ./scripts/generate-traffic.sh   # tunable
```

---

## Configuration

Key knobs in `src/main/resources/application.yaml` (prefix `flightbooking.`):

| Property | Default | Purpose |
|---|---|---|
| `kafka.enabled` | `true` | use real Kafka (`false` → logging publishers, `local` profile) |
| `graph.refresh-interval-ms` | `300000` | in-memory flight-graph rebuild cadence |
| `search.max-legs` | `3` | max connecting flights per journey |
| `search.min/max-layover-minutes` | `60` / `720` | valid connection window |
| `booking.payment-ttl-seconds` | `600` | how long a `PENDING_PAYMENT` booking may wait before expiry |
| `booking.expiry-sweep-ms` | `300000` | sweeper cadence |
| `refund.percentage` | `80` | flat partial-refund percentage on cancellation |
| `seed.flight-count` / `days-ahead` | `2500` / `14` | synthetic seed data size / horizon |

Profiles:
- **default** — Percona/MySQL + real Spring Kafka (expects `docker compose up`).
- **`local`** — H2 in-memory + Kafka disabled.

---

## Project layout

```
src/main/java/club/cred/flightbookingsystem
├── domain        # entities + enums
├── repository    # Spring Data JPA repositories
├── dto           # request/response records
├── search        # FlightGraph + BFS SearchService
├── booking       # BookingService, state machine, seats, cancellation, sweeper, refund policy
├── messaging     # event publishers/messages (+ kafka/ and local/ implementations)
├── controller    # REST controllers + exception handler + payment simulator
├── config        # DataSeeder, scheduling
└── metrics       # custom Micrometer metrics
monitoring/       # Prometheus config + Grafana provisioning & dashboards
scripts/          # generate-traffic.sh
```

---

## Troubleshooting

- **App won't start on the default profile** — ensure `docker compose ps` shows Percona healthy
  and Kafka up; the app expects `localhost:3306` and `localhost:9092`.
- **Port already in use (8080)** — stop the previous run, e.g. `kill $(lsof -nP -iTCP:8080 -sTCP:LISTEN -t)`.
- **Just want to poke the API without Docker** — use the `local` profile (Option A).

