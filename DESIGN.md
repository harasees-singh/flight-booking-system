# Flight Booking System — Design Document

> Spring Boot service that mimics a single-airline flight booking system with high-QPS search, booking, and cancellation flows.

---

## 1. Requirements

### 1.1 Functional
1. **Search** flights (direct + connecting, up to 3 legs) between source `A` and destination `B` for a given **date** and **number of passengers**.
2. **Book** a journey (one or more connected flights) for a list of passengers (max 10).
3. **Cancel** a booking → validate → unblock seats → emit a (partial) refund event (refund processing is a black box).

### 1.2 Non-Functional
| Concern | Target |
|---|---|
| Search QPS | Very high (read-heavy) → in-memory graph |
| Booking consistency | Strong (no overselling seats) |
| Refund/payment | Asynchronous, black-box (event driven) |
| Observability | Grafana dashboards (5xx/4xx/2xx, Kafka lag, API latency, search patterns) |

### 1.3 Constraints / Scale
- Only **1 airline**.
- **Payment** is a black box.
- Multiple **aircraft models**.
- **2000–3000** distinct direct flights.
- At most **3 connecting flights** per journey.
- At most **10 passengers** per booking.
- **Seats are a count only** — there is no individual seat identity or characteristic (no seat number, aisle/window/class, etc.). A flight simply tracks `totalSeats` and `seatsRemaining`.

### 1.4 Agreed Design Decisions
| # | Topic | Decision |
|---|---|---|
| 1 | Graph sync | Load once at startup + **periodic scheduled refresh** (every N minutes). Search is eventually consistent on seat counts. |
| 2 | Search seat accuracy | **Approximate** availability in search results; exact `seatsRemaining >= pax` enforced atomically at **booking time**. |
| 3 | Journey fare | Per-passenger fare = **sum of each leg's `baseFare`** (flat, same for all passengers). |
| 4 | Refund policy | **Flat percentage** — refund 80%, 20% cancellation fee (configurable). |
| 5 | Infra from Phase 1 | Add **Docker Compose (Percona + Kafka)** now; wire real JPA + Spring Kafka from Phase 1. |
| 6 | Payment callback | **Kafka consumer** on `payment.callback` topic (payment system publishes confirm/deny). |

---

## 2. High-Level Design (HLD)

```
                                  ┌──────────────────────────┐
        (high QPS reads)          │     Search Service       │
   client ───► API Gateway ─────► │  in-mem Flight Graph     │
                    │             │  BFS depth ≤ 3           │
                    │             └──────────────────────────┘
                    │
                    │             ┌──────────────────────────┐     ┌──────────────┐
                    ├───────────► │    Booking Service       │ ───►│  Seat Locks  │
                    │             │  state machine + seats   │     │  (DB rows)   │
                    │             └──────────┬───────────────┘     └──────────────┘
                    │                        │ emits events
                    │                        ▼
                    │             ┌──────────────────────────┐
                    └───────────► │  Cancellation Service    │
                                  └──────────┬───────────────┘
                                             │
                                   Kafka ────┼────► payment.refund (black box consumer)
                                             └────► booking.events (audit/metrics)
```

### 2.1 Components
- **Search Service** — read path. Maintains an **in-memory directed graph** (node = city/airport, edge = flight). **BFS** up to depth 3 to enumerate journeys. BFS is used deliberately: it explores level-by-level so it finds journeys with the fewest legs first and reliably enumerates all valid paths within the depth limit; DFS can dive down sub-optimal branches and produce incorrect/sub-optimal path ordering.
- **Booking Service** — write path. Owns the booking **state machine**, seat locking, and the payment callback handling.
- **Cancellation Service** — validates booking, releases seats, publishes refund event.
- **Refund/Payment** — black box; integrated via **Kafka** events.
- **Graph Loader** — bootstraps and periodically refreshes the flight graph from the DB. The graph uses an **immutable-snapshot / copy-on-write** scheme: each refresh builds a brand-new adjacency map and swaps it via a single `volatile` reference store (atomic + safely published). Readers are lock-free and the old map is never mutated, so in-flight searches holding the previous reference continue safely until they finish (then it's GC'd).

### 2.2 Data Stores
- **RDBMS (Percona MySQL)** — source of truth for `aircraft`, `flight`, `booking`, `refund`. Run locally via the **Percona Docker image**. Seat decrement uses optimistic/pessimistic locking to avoid oversell.
- **Kafka** — `payment.callback` (inbound from payment system), `payment.refund` (outbound to refund black box), `booking.events`. Run locally via a **Kafka Docker image**, integrated with **Spring Kafka**.

---

## 3. Low-Level Design (LLD)

### 3.1 Domain Models

```
Aircraft {
  id
  model            // e.g. A320, B737
  totalSeats
}

Flight {
  id
  aircraftId
  source           // city / airport code
  destination
  departureTime
  arrivalTime
  flightDurationMin
  totalSeats
  seatsRemaining
  baseFare
  version          // optimistic locking
}

Booking {
  id
  state                      // BookingState
  passengers      : List<Passenger>
  passengerRate   : Map<Passenger, Money>   // per-passenger fare
  flights         : List<Flight>            // ordered legs of the journey
  totalAmount
  createdAt / updatedAt
}

Passenger {
  id
  name
  age
}

Refund {
  id
  bookingId
  amount
  state            // RefundState
}
```

### 3.2 Enums

**BookingState**
```
INITIATED ──► PENDING_PAYMENT ──► SUCCESS
                  │                  │
                  ▼                  ▼
               FAILURE           CANCELLED
            (unlock seats)     (unlock seats)
```
| State | Meaning | Seat effect |
|---|---|---|
| INITIATED | request received | none |
| PENDING_PAYMENT | seats locked, awaiting payment | lock |
| SUCCESS | payment confirmed | committed |
| FAILURE | payment failed/timeout | unlock |
| CANCELLED | user cancelled | unlock |

**RefundState**: `INITIATED → PROCESSING → COMPLETED / FAILED`
> The included black-box refund stub (`RefundProcessorStub`) currently transitions `INITIATED → COMPLETED` directly on consuming `payment.refund`; `PROCESSING`/`FAILED` are reserved for a real asynchronous processor.

### 3.3 Search Algorithm
- Graph: `Map<City, List<Flight>>` adjacency list, rebuilt on schedule.
- **BFS** from `A`, expanding level-by-level, each edge whose `departureTime` is on the requested date and after the previous leg's arrival (+ min layover), pruning paths > depth 3 and flights with `seatsRemaining < pax`.
- BFS (not DFS): level-order traversal surfaces the fewest-leg journeys first and avoids the incorrect / sub-optimal path ordering DFS can produce when diving deep into a branch.
- Result = list of journeys (each = ordered list of flights) + total fare/time.

### 3.4 Booking Flow (two interactions)
The booking is **not** a single call — it is a synchronous seat-block call followed by an asynchronous payment callback.

**Call 1 — `POST /bookings` (synchronous, blocks seats):**
1. Validate pax ≤ 10, flights connect, date valid.
2. **Lock seats** for each leg (atomic conditional update `seatsRemaining -= pax WHERE seatsRemaining >= pax`).
3. Create booking in state `PENDING_PAYMENT`; persist passengers & per-passenger rates.
4. Return `bookingId` + amount to the client, who proceeds to pay at the (black-box) payment system.

**Call 2 — payment callback via Kafka (async confirmation from payment system):**
The payment system publishes a confirm/deny message to the **`payment.callback`** topic; the Booking Service consumes it.
5. On **payment success** → state `SUCCESS`, seats committed; emit `booking.events`.
6. On **payment failure / timeout** → state `FAILURE`, **unlock seats** (`seatsRemaining += pax`); emit `booking.events`.
7. **Expiry of stale `PENDING_PAYMENT`** — handled by a **scheduled sweeper** that runs every **5 minutes**. It queries for bookings stuck in `PENDING_PAYMENT` older than the TTL (`createdAt < now − ttl`) and expires each one. This is **durable across restarts** (state is re-derived from the DB every run, so nothing is lost if the app bounces) and needs no in-memory timers.
   - The expiry must be **idempotent** and guarded by a conditional update: `UPDATE booking SET state='FAILURE' WHERE id=? AND state='PENDING_PAYMENT'`; seats are unlocked **only if that update actually changed a row**, preventing a double-unlock race with the payment callback.

### 3.5 Cancellation Flow
1. `POST /bookings/{id}/cancel`.
2. **Validate** booking is cancellable (state `SUCCESS`), determine seats to release.
3. **Unblock seats** (`seatsRemaining += pax`).
4. Set state `CANCELLED`.
5. Compute **partial refund** amount (policy-based) → publish `payment.refund` event; create `Refund(INITIATED)`.

### 3.6 REST API
| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/flights/search?src=&dst=&date=&pax=` | search journeys |
| `POST` | `/api/v1/bookings` | create booking (blocks seats → `PENDING_PAYMENT`) |
| `GET` | `/api/v1/bookings/{id}` | booking details |
| `POST` | `/api/v1/bookings/{id}/cancel` | cancel + refund |
| `GET` | `/api/v1/aircrafts`, `/api/v1/flights` | admin/seed lookups |

> **Test harness:** `POST /api/v1/_sim/payment-callback?bookingId=&success=` publishes a message to `payment.callback`, standing in for the black-box payment system. It is only registered when Kafka is enabled (`flightbooking.kafka.enabled=true`).

### 3.7 Concurrency & Consistency
- Seat updates via an **atomic conditional UPDATE** (`... SET seatsRemaining = seatsRemaining - pax WHERE seatsRemaining >= pax`); the `Flight.version` column additionally provides `@Version` optimistic locking. Together these prevent oversell under concurrency — verified by a parallel-booking integration test where, out of many racing requests for a 6-seat flight, exactly the affordable number win and `seatsRemaining` never goes negative.
- **State transitions are idempotent and race-safe** via a single-statement compare-and-set (`UPDATE booking SET state=:new WHERE id=:id AND state=:expected`). Only the winning update unlocks/commits seats, so a duplicate payment callback, or an expiry sweep that races a confirmation, is a harmless no-op.
- **Note:** there is currently **no client-supplied idempotency key** on `POST /bookings`; a retried create blocks a fresh set of seats. (A future `Idempotency-Key` header is the natural extension.)
- Search is eventually consistent (graph refresh interval), acceptable for the read path.

---

## 4. Observability (Grafana)
| Metric | Source |
|---|---|
| 5xx / 4xx / 2xx rates | Micrometer HTTP server metrics (`http.server.requests`) |
| API latency (p50/p95/p99) | `http.server.requests` histogram + percentiles/SLO buckets |
| Kafka topic lag | Kafka consumer lag (Micrometer Kafka metrics) |
| Search query patterns | `flightbooking.search.requests` — tags `src`, `dst`, `outcome` (hit/miss) |
| Booking outcomes | `flightbooking.booking.transitions` — tag `state`; `flightbooking.booking.seat_rejections` |

**Custom meters** live in the `metrics` package (`SearchMetrics`, `BookingMetrics`). Latency histograms, app-side percentiles and SLO buckets for `http.server.requests` are configured in `application.yaml`.

Exposed via `spring-boot-starter-actuator` + `micrometer-registry-prometheus` at `/actuator/{health,info,prometheus,metrics}`. A **Prometheus + Grafana stack** ships in `docker-compose.yml` (Prometheus scrapes the app; Grafana auto-provisions the datasource and the `flightbookingsystem-overview` dashboard under `monitoring/`).

**Tracing** — Micrometer Tracing (Brave bridge) emits a trace per request, propagated across HTTP and Kafka, with `traceId`/`spanId` in every log line for correlation.

---

## 5. Package Layout
```
club.cred.flightbookingsystem
├── domain            // entities (Aircraft, Flight, Booking, Passenger, Refund) + enums
├── repository        // Spring Data JPA repositories
├── dto               // request/response records
├── search            // FlightGraph (in-mem graph) + SearchService (BFS)
├── booking           // BookingService, BookingStateMachine, SeatService,
│                     //   CancellationService, RefundPolicy, BookingExpirySweeper, exceptions
├── messaging         // event abstractions (publishers, messages, KafkaTopics)
│   ├── kafka         //   Spring Kafka publishers, payment.callback consumer,
│   │                 //     refund processor stub, topic config
│   └── local         //   logging fallbacks when Kafka is disabled (local profile)
├── controller        // REST controllers + ApiExceptionHandler + payment simulator
├── config            // DataSeeder, SchedulingConfig
└── metrics           // custom Micrometer metrics (SearchMetrics, BookingMetrics)
```
> Note: cancellation/refund logic lives in the `booking` and `messaging` packages rather than standalone `cancellation`/`refund` packages.

---

## 6. Phased Build Plan
1. **Phase 1** ✅ — Domain models, repositories, seed data, in-memory graph + search API.
2. **Phase 2** ✅ — Booking flow (sync seat-block call + async payment callback) with seat locking & state machine.
3. **Phase 3** ✅ — Cancellation + refund event (Spring Kafka), payment black-box stub.
4. **Phase 4** ✅ — Observability (actuator, Prometheus). Custom Micrometer metrics (`metrics` package), HTTP latency histograms, and a Prometheus + Grafana stack in Docker Compose.

---

## 7. Configuration & Profiles
- **Default profile** — Percona/MySQL datasource + real Spring Kafka (`flightbooking.kafka.enabled=true`). Brought up via `docker-compose.yml` (Percona, Kafka, Prometheus, Grafana).
- **`local` profile** — in-memory H2 (`create-drop`) with Kafka **disabled**; booking events and refunds go to logging fallback publishers (`messaging.local`) and the payment callback path is exercised in-process. Used for fast runs and most tests.

Key tunables (`application.yaml`, prefix `flightbooking.`):

| Property | Default | Purpose |
|---|---|---|
| `graph.refresh-interval-ms` | 300000 | flight-graph rebuild cadence |
| `search.max-legs` | 3 | max connecting flights per journey |
| `search.min/max-layover-minutes` | 60 / 720 | valid connection window |
| `booking.payment-ttl-seconds` | 600 | PENDING_PAYMENT staleness threshold |
| `booking.expiry-sweep-ms` | 300000 | sweeper cadence |
| `refund.percentage` | 80 | flat partial-refund percentage |
| `seed.flight-count` / `days-ahead` | 2500 / 14 | synthetic seed data size/horizon |

---

## 8. Testing
- **Unit tests** — services and policies with Mockito: state machine, booking/cancellation services, refund policy, seat service, expiry sweeper (incl. resilience), payment-callback consumer, refund processor stub, exception handler, and the custom metrics.
- **Integration tests** (`@SpringBootTest`, random-port server, H2) drive the real HTTP endpoints end-to-end:
  - booking happy path: search → create → confirm → SUCCESS;
  - cancellation → CANCELLED + seats released + partial refund;
  - payment denial and **timeout/expiry** → FAILURE + seats released;
  - **sweeper boundary**: a fresh (within-TTL) booking is not expired, an aged one is;
  - **race-safety**: late callback after expiry is ignored; duplicate confirmation is idempotent;
  - **no oversell** under real parallel load; multi-leg journey blocks/releases every leg;
  - overbooking and cancel-of-non-confirmed → `409`.
- **End-to-end Kafka test** — `@EmbeddedKafka` exercises the full async pipeline: payment simulator → `payment.callback` consumer → SUCCESS, then cancel → `payment.refund` → processor completes the refund.

---

## 9. Resilience & Operability
- **Durable expiry** — the sweeper re-derives stale bookings from the DB each run (no in-memory timers), so it survives restarts. Each booking is expired in its own try/catch and a DB query failure is swallowed-and-logged so one bad row or transient outage doesn't stop the sweep.
- **Graph refresh fault tolerance** — a failed rebuild logs an error and **retains the previous immutable snapshot** rather than going dark.
- **Kafka publish safety** — sends use async callbacks; a failed publish is logged at `ERROR` (a dropped `payment.refund` is flagged as money-owed-not-processed).
- **Kafka consume safety (retry + DLT)** — consumers use an `ErrorHandlingDeserializer` so a poison/undeserializable record can't block the partition. A `DefaultErrorHandler` retries transient failures with a fixed back-off and then routes the record to a per-topic **dead-letter topic** (`<topic>.DLT`); deserialization/illegal-argument errors are non-retryable and go straight to the DLT.
- **Sanitized errors** — an `@RestControllerAdvice` (extending `ResponseEntityExceptionHandler`) maps standard 4xx and domain exceptions precisely, and a catch-all returns a **sanitized 500 with a correlation `errorId`** (logged server-side) so stack traces never leak to clients.
- **Distributed tracing** — Micrometer Tracing (Brave) generates a trace per request and **propagates it across HTTP and Kafka** (`observation-enabled`), so a booking journey is one trace end-to-end; `traceId`/`spanId` are included in every log line.
- **Structured logging** — per-package levels and a thread/logger/trace console pattern (`application.yaml`) make booking, Kafka, and scheduler activity traceable in production.


