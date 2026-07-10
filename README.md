# UPI Offline Mesh

**You're in a basement. Zero bars. Your friend needs ₹500 for the pizza guy standing at the door.**

You send it anyway. Your phone encrypts the payment and hands it to the nearest stranger's phone over Bluetooth. That phone hands it to another. Twenty minutes later, someone in that human chain walks outside, their phone finds a signal, and — without them doing anything — your payment quietly settles on a server that's never heard of any of these people until this exact moment.

That's the system this repository builds: encrypted payments that survive having **no internet anywhere in the chain**, until, eventually, somewhere, there's just a little bit.

This is the full-stack build — a Spring Boot backend doing real cryptography and real settlement, a Postgres + Redis production-shaped data layer, a React dashboard that visualizes packets hopping through the mesh live, and a local LLM that turns your transaction history into plain-English answers, grounded strictly in numbers the backend already computed — never numbers it guessed.

---

## Table of Contents

1. [What this proves](#what-this-proves)
2. [Quick start](#quick-start)
3. [Architecture](#architecture)
4. [The three hard problems](#the-three-hard-problems)
5. [Why the mesh topology looks the way it does](#why-the-mesh-topology-looks-the-way-it-does)
6. [The insights layer — and two times it lied to me](#the-insights-layer--and-two-times-it-lied-to-me)
7. [Tech stack](#tech-stack)
8. [Folder structure](#folder-structure)
9. [API reference](#api-reference)
10. [Running the demo, step by step](#running-the-demo-step-by-step)
11. [What's NOT real](#whats-not-real-and-what-would-change-for-production)
12. [Honest limitations](#honest-limitations)
13. [License](#license)

---

## What this proves

Five things, working end to end, on infrastructure that actually resembles production rather than a toy:

1. **A payment can travel through untrusted strangers' phones** without any of them reading or tampering with it. Hybrid RSA-2048 + AES-256-GCM encryption — the same pattern TLS itself uses.
2. **A packet can arrive at the backend from multiple directions at once and still settle exactly once.** Redis-backed atomic idempotency, the exact same `SET NX EX` pattern a real distributed payments system would use.
3. **A tampered or replayed packet is rejected before it touches the ledger.** GCM's authentication tag catches tampering; a 24-hour freshness window catches replay.
4. **A user can ask their spending activity a question in plain English and get a factually grounded answer** — with two documented cases (below) of me deliberately breaking that guarantee to understand exactly where its edges are.
5. **The whole thing comes up with one command.** `docker compose up -d --build` — Postgres, Redis, a local LLM, and the backend, all correctly networked, no manual setup.

---

## Quick start

```bash
git clone <this-repo>
cd upi-offline-mesh
docker compose up -d --build
```

First run pulls Postgres, Redis, and Ollama images, builds the Spring Boot jar inside a multi-stage Docker build, and pulls the LLM model:

```bash
docker exec -it upimesh-ollama ollama pull llama3
```

Then, for the frontend:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. That's the whole setup — no local Java, Maven, Postgres, or Redis installation required; only Docker and Node.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          SENDER PHONE (offline)                          │
│   PaymentInstruction { sender, receiver, amount, pinHash, nonce, time }  │
│                │                                                          │
│                ▼ hybrid-encrypt with server's RSA public key             │
│    MeshPacket { packetId, ttl, createdAt, ciphertext }                   │
└───────────────────────────────┬────────────────────────────────────────┘
                                 │ Bluetooth-style gossip (restricted topology,
                                 │ see below — sender can't reach a bridge directly)
                                 ▼
      ┌──────────┐   hop   ┌──────────┐   hop   ┌─────────┐
      │ stranger │◀───────▶│ stranger │◀───────▶│ bridge  │◀── walks outside,
      └──────────┘         └──────────┘         └────┬────┘    gets signal
                                                       │
                                                       ▼ HTTPS POST
┌──────────────────────────────────────────────────────────────────────────┐
│                    SPRING BOOT BACKEND (Docker container)                │
│  /api/bridge/ingest                                                       │
│       │                                                                   │
│       ▼ [1] SHA-256 hash the ciphertext                                  │
│       ▼ [2] IdempotencyService.claim(hash) — Redis SETNX, atomic          │
│       ▼ [3] HybridCryptoService.decrypt() — RSA-OAEP unwraps AES key,     │
│       │      AES-GCM decrypts + authenticates in the same step           │
│       ▼ [4] Freshness check — signedAt within 24h                         │
│       ▼ [5] SettlementService.settle() — @Transactional, @Version-locked  │
└──────────┬─────────────────────────────────────────┬─────────────────────┘
           │                                          │
           ▼                                          ▼
   ┌───────────────┐                        ┌──────────────────┐
   │  PostgreSQL   │                        │  InsightService   │
   │  (Docker vol) │                        │  → Ollama/llama3  │
   └───────────────┘                        │  narrates numbers  │
           ▲                                 │  Java already      │
           │                                 │  computed           │
           │                        ┌────────┴──────────┐
           │                        │ React dashboard     │
           └────────────────────────│ live mesh graph,     │
                                     │ ledger, chat widget  │
                                     └───────────────────────┘
```

---

## The three hard problems

### Problem 1 — Untrusted intermediaries

A random stranger's phone is physically carrying your transaction. **Solution: hybrid encryption.**

The sender encrypts the payload with the server's RSA-2048 public key. RSA can't directly encrypt anything much bigger than ~245 bytes, so a fresh AES-256 key is generated per packet, encrypts the actual JSON payload with AES-GCM, and only the small AES key gets RSA-wrapped:

```
[256B RSA-OAEP-wrapped AES key][12B GCM IV][AES-GCM ciphertext + 16B auth tag]
```

**Why GCM specifically:** it's authenticated encryption. Flip one bit anywhere in transit and decryption throws — no separate signature-checking code needed, tampering detection is a side effect of decryption itself succeeding or failing.

### Problem 2 — The duplicate storm

Three strangers all happen to walk outside within the same second, all carrying the same packet. Naively processing all three debits the sender three times.

**Solution: Redis `SETNX` on the ciphertext hash**, checked *before* any decryption happens:

```java
Boolean firstClaim = redis.opsForValue()
    .setIfAbsent(packetHash, timestamp, Duration.ofSeconds(ttl));
```

Atomic at the Redis server level — even across multiple backend instances, not just multiple threads in one JVM. Hashing the *ciphertext* (not the outer `packetId`, which an intermediate could rewrite, and not the decrypted plaintext, which would waste CPU decrypting duplicates before rejecting them) is the detail that makes this both fast and tamper-resistant.

### Problem 3 — Replay attacks

An attacker who captured a ciphertext weeks ago replays it later. Two independent defenses, both sealed inside the GCM-authenticated payload so they can't be forged without breaking the auth tag:

- `signedAt` — rejected if older than 24 hours.
- `nonce` — a fresh UUID per payment, so two *legitimate* identical-amount payments still produce different ciphertexts and settle independently, while a byte-identical replay collides on the idempotency hash and gets dropped.

---

## Why the mesh topology looks the way it does

Early versions of this demo connected every simulated device to every other one — which meant a packet could jump from the sender straight to the bridge node in a single hop. Realistic mesh networks don't work that way: someone in a basement can't magically reach the one person standing outside.

So the simulator's adjacency is deliberately restricted:

```java
"phone-alice",  → {stranger1, stranger2}          // no direct line to the bridge
"stranger1",    → {phone-alice, stranger2, bridge}
"stranger2",    → {phone-alice, stranger1, bridge}
"phone-bridge", → {stranger1, stranger2}          // no direct line to the sender
```

A payment now needs a genuine two-hop relay — sender → stranger → bridge — before it can settle, which is a more honest simulation of what "offline, deferred settlement" actually means, and makes the multi-hop count visible in the ledger (`hopCount: 2`) mean something real rather than being an artifact of an accidentally fully-connected graph.

---

## The insights layer — and two times it lied to me

The `InsightService` lets you ask natural-language questions about your spending, backed by a local Ollama/Llama 3 model — no cloud API, no data ever leaving the machine. The design rule going in was simple: **Java computes every number; the LLM only narrates it in a sentence.** The prompt explicitly forbids the model from doing its own arithmetic.

That rule got tested twice, on purpose, and both times it caught something worth documenting rather than hiding.

**Case 1 — asked for something not explicitly computed.** I asked for the average transaction size. `InsightContext` at the time only carried totals and a count — no average. Rather than say "I don't have that," the model divided the numbers itself: **₹2375 ÷ 6, and got it wrong** (₹396.17 instead of the correct ₹395.83). Fix: compute the average in Java with `BigDecimal` and `RoundingMode.HALF_UP`, and add an explicit instruction — *"if a number isn't listed below, say you don't have it rather than guessing."*

**Case 2 — asked for a per-counterparty breakdown that didn't exist yet.** "How much have I sent Bob?" `InsightContext` only had overall totals, not a per-recipient breakdown. The model picked a real number that happened to appear in the raw transaction list handed to it (a single ₹751 payment to Bob) and presented it as *the* answer — when the actual total sent to Bob across multiple payments was ₹1,875. This is a sharper failure than Case 1: not bad arithmetic, but a plausible-sounding number stated as fact. Fix: compute a real `sentByCounterparty` / `receivedByCounterparty` breakdown in Java and hand it to the model directly, so there's no gap left for it to fill in on its own.

**The pattern that generalizes, and the honest limitation that remains:** every time the model was caught guessing, the fix was never a stronger warning in the prompt — it was closing the data gap so there was nothing left to guess. The current design still means every new *kind* of question (e.g. spending by category, once categories exist) needs a new precomputed aggregate added by hand. A proper fix is a tool-calling redesign — the LLM requests a specific query function by name and arguments, Java runs the real one, and the model only narrates that single answer — which would let it answer arbitrary breakdowns without prompt changes. That's a deliberate scope cut for now, documented rather than quietly left unfixed.

---

## Tech stack

| Layer | Technology | Why |
|---|---|---|
| Backend | Spring Boot 4.1, Java 17 | Full crypto/settlement pipeline |
| Database | PostgreSQL 16 (Docker) | Real persistence, survives restarts |
| Cache / idempotency | Redis 7 (Docker) | Atomic `SETNX`, TTL-based eviction, distributed-safe |
| Schema migrations | Flyway | Version-controlled schema, no `ddl-auto=create-drop` |
| Frontend | React 18 + Vite | Live mesh visualization, ledger, chat widget |
| Styling | Tailwind CSS 4 | Custom "signal room" design tokens, no default template look |
| Icons | lucide-react | Floating chat widget toggle |
| LLM | Ollama + Llama 3, local | No cloud API, no data leaves the machine, no per-token cost |
| Boilerplate | Lombok | `@Getter`/`@Setter`/`@RequiredArgsConstructor` on services; explicit (non-`@Data`) annotations on JPA entities to avoid lazy-loading foot-guns |
| Containerization | Docker Compose, multi-stage Dockerfile | One-command full-stack startup |

---

## Folder structure

```
upi-offline-mesh/
├── docker-compose.yml
├── Dockerfile
├── README.md
├── mvnw, mvnw.cmd, .mvn/
├── pom.xml
├── src/main/
│   ├── resources/
│   │   ├── application.properties
│   │   └── db/migration/
│   │       └── V1__init_schema.sql
│   └── java/org/learingspring/upimesh/
│       ├── UpImeshApplication.java
│       ├── crypto/
│       │   ├── ServerKeyHolder.java
│       │   └── HybridCryptoService.java
│       ├── model/
│       │   ├── Account.java, AccountRepository.java
│       │   ├── Transaction.java, TransactionRepository.java
│       │   ├── MeshPacket.java
│       │   └── PaymentInstruction.java
│       ├── service/
│       │   ├── IdempotencyService.java      (Redis-backed)
│       │   ├── SettlementService.java       (@Transactional debit/credit/ledger)
│       │   ├── BridgeIngestionService.java  (hash → claim → decrypt → settle)
│       │   ├── MeshSimulatorService.java    (restricted-adjacency gossip)
│       │   ├── VirtualDevice.java
│       │   ├── DemoService.java             (simulates a sender phone)
│       │   └── InsightService.java          (LLM narration over computed data)
│       ├── controller/
│       │   ├── ApiController.java
│       │   └── InsightController.java
│       └── config/
│           └── WebConfig.java               (CORS for the React dev server)
└── frontend/
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── App.jsx
        ├── index.css                        (Tailwind + design tokens)
        ├── api/client.js
        └── components/
            ├── MeshView.jsx                 (animated SVG packet-hop graph)
            ├── AccountBalances.jsx
            ├── LedgerTable.jsx
            └── InsightChat.jsx               (floating LLM chat widget)
```

---

## API reference

| Method | Path | What it does |
|---|---|---|
| GET | `/api/server-key` | Server's RSA public key (base64) |
| GET | `/api/accounts` | All accounts and balances |
| GET | `/api/transactions` | Last 20 settled transactions |
| GET | `/api/mesh/state` | Current state of every virtual device |
| POST | `/api/demo/send` | Simulate a sender phone — encrypt + inject packet |
| POST | `/api/mesh/gossip` | Run one gossip round (restricted adjacency) |
| POST | `/api/mesh/flush` | Bridge nodes upload everything they hold, in parallel |
| POST | `/api/mesh/reset` | Clear mesh state + idempotency cache |
| POST | `/api/bridge/ingest` | **The production endpoint** — real bridges POST here |
| GET | `/api/insights/context?vpa=&days=` | Preview the computed numbers behind an insight answer |
| POST | `/api/insights/ask` | `{ vpa, question, days }` → LLM-narrated, Java-grounded answer |

---

## Running the demo, step by step

1. **Inject Payment** — simulates Alice's phone building, encrypting, and dropping a packet onto the mesh.
2. **Run Gossip Round** — click **twice**. Because of the restricted topology, a packet needs a real two-hop path (sender → stranger → bridge) to arrive.
3. **Flush Bridges** — the bridge node uploads everything it's holding to the real `/api/bridge/ingest` pipeline. Watch balances and the ledger update live.
4. **Ask the chat widget** (bottom-right) something like *"How much have I sent Bob?"* — grounded entirely in what Postgres actually has recorded.
5. **Reset Mesh** to clear state and idempotency cache, and try again — including deliberately sending the same amount twice in a row to watch the idempotency guarantee hold.

---

## What's NOT real (and what would change for production)

| What's in the demo | What it would be in production |
|---|---|
| RSA keypair regenerated on every startup | Private key in an HSM/KMS (AWS KMS, HashiCorp Vault) |
| Software-simulated mesh, fixed adjacency | Real BLE GATT / Wi-Fi Direct between physical phones |
| One settlement service owning the ledger | Integration with NPCI / a real bank core |
| No auth on `/api/bridge/ingest` | Mutual TLS or signed bridge-node certificates |
| In-memory-seeded demo accounts | Real KYC'd users, real bank-verified PIN checks |
| PIN accepted but never verified | PIN hash checked against the account before settlement |
| Local Ollama, single instance | Still viable in production for data-sensitivity reasons — but load-balanced across replicas |
| Insights answer from precomputed aggregates | Tool-calling redesign for arbitrary-breakdown questions |
| Structured logs to console | Structured logs to a SIEM, alerting on `INVALID` spikes |

---

## Honest limitations

These aren't implementation bugs — they're inherent to "no internet, anywhere in the chain," and worth being upfront about:

1. **The receiver can't verify the sender has funds at the moment of the handshake.** A shown "₹500 sent" is an IOU until the backend actually settles it — if the sender's account is empty by the time a bridge uploads, the transaction is `REJECTED` and the receiver has no recourse. This is exactly why real offline UPI (UPI Lite) uses a pre-funded, hardware-backed wallet instead.
2. **A malicious sender can double-spend offline** — send the same balance to two different people in two different basements; whichever packet reaches the backend first wins, the other is rejected. Same root cause as #1.
3. **Real Bluetooth mesh is genuinely hard** — background BLE is heavily throttled on modern Android, iOS peripheral mode is locked down. This demo sidesteps that entirely with a software simulator rather than pretending it's a solved problem.
4. **Metadata leakage** — a stranger's phone can't read your payment, but the fact that *a* payment passed through their device at all is itself information. A real deployment would need to think through what that implies under seizure or subpoena.
5. **The insights layer answers from a fixed set of precomputed aggregates**, not arbitrary questions — see the section above for exactly why, and what a proper fix looks like.

For a portfolio project: naming this honestly as **"mesh-routed deferred settlement with a locally-grounded LLM insights layer"**, rather than overselling it as "real-time offline UPI" or "AI-powered payments," is a stronger and more defensible pitch — and the cryptography, idempotency, and settlement logic underneath it is genuine, production-shaped engineering.

---

## License

Demo code, no license. Use it however you want for learning.
