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
- **Graph Loader** — bootstraps and periodically refreshes the flight graph from the DB.

### 2.2 Data Stores
- **RDBMS (Percona MySQL)** — source of truth for `aircraft`, `flight`, `booking`, `refund`. Run locally via the **Percona Docker image**. Seat decrement uses optimistic/pessimistic locking to avoid oversell.
- **Kafka** — `payment.callback` (inbound from payment system), `payment.timeout` (per-booking delayed expiry), `payment.refund` (outbound to refund black box), `booking.events`. Run locally via a **Kafka Docker image**, integrated with **Spring Kafka**.

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
7. **Expiry of stale `PENDING_PAYMENT`** — handled via a **per-booking delayed Kafka message** (not a polling sweeper). At seat-block time a message is published to the **`payment.timeout`** topic to be consumed after a fixed delay (TTL). On consume, expire the booking and unlock seats. This gives near-exact timing and avoids full-table scans.
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

### 3.7 Concurrency & Consistency
- Seat updates via **conditional UPDATE** (or `@Version` optimistic lock) to prevent oversell.
- Booking creation is **idempotent** via client request id.
- Search is eventually consistent (graph refresh interval), acceptable for read path.

---

## 4. Observability (Grafana)
| Metric | Source |
|---|---|
| 5xx / 4xx / 2xx rates | Micrometer HTTP server metrics |
| Kafka topic lag | Kafka consumer lag exporter |
| API latency (p50/p95/p99) | Micrometer timers |
| Search query patterns | custom counter tags (src, dst) |

Expose via `spring-boot-starter-actuator` + `micrometer-registry-prometheus`; Grafana scrapes Prometheus.

---

## 5. Package Layout (planned)
```
club.cred.flightbookingsystem
├── domain            // entities + enums
├── repository        // JPA repositories
├── dto               // request/response models
├── search            // graph + BFS search service
├── booking           // booking service + state machine
├── cancellation      // cancellation service
├── refund            // refund event publisher
├── controller        // REST controllers
├── config            // kafka, seed data, datasource
└── metrics           // custom Micrometer metrics
```

---

## 6. Phased Build Plan
1. **Phase 1** — Domain models, repositories, seed data, in-memory graph + search API.
2. **Phase 2** — Booking flow (sync seat-block call + async payment callback) with seat locking & state machine.
3. **Phase 3** — Cancellation + refund event (Spring Kafka), payment black box stub.
4. **Phase 4** — Observability (actuator, Prometheus).


