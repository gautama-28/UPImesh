# UPI Offline Mesh

**Scenario: you're in a basement with zero signal. Your friend needs ₹500 for the pizza guy at the door.**

You send it anyway. Your phone encrypts the payment and hands it off to the nearest stranger's phone over Bluetooth. That phone passes it to another. Eventually, someone in that chain walks outside, their phone picks up a signal, and the payment settles on a server that's never interacted with any of these people until that exact moment.

That's what this project is: encrypted payments that can survive having no internet anywhere in the chain, until eventually, somewhere, there's a little bit of signal to work with.

**Demo**
![Demo](demo.gif)

This is the full-stack version of that idea — a Spring Boot backend doing the actual cryptography and settlement logic, Postgres + Redis as the data layer, a React dashboard that shows packets hopping through the mesh in real time, and a local LLM that answers questions about your transaction history using numbers the backend already computed, not numbers it made up.

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
12. [Limitations](#limitations)
13. [License](#license)

---

## What this proves

Five things work end to end here, on infrastructure that's a lot closer to how a real system would be built than a typical class project:

1. **A payment can travel through untrusted strangers' phones** without any of them being able to read or tamper with it. This uses hybrid RSA-2048 + AES-256-GCM encryption — the same general approach TLS uses.
2. **A packet can arrive at the backend from multiple directions at once and still only settle once.** This is handled with a Redis-backed atomic idempotency check, using the same `SET NX EX` pattern you'd see in a real distributed payments system.
3. **A tampered or replayed packet gets rejected before it touches the ledger.** GCM's authentication tag catches tampering, and a 24-hour freshness window catches replay attempts.
4. **You can ask a plain-English question about your spending and get an answer grounded in real numbers** — I've got two documented cases below where I deliberately broke that guarantee to see exactly where it fails.
5. **The whole thing comes up with one command.** `docker compose up -d --build` starts Postgres, Redis, a local LLM, and the backend, all networked correctly, with no manual setup steps.

---

## Quick start

```bash
git clone <this-repo>
cd upi-offline-mesh
docker compose up -d --build
```

First run pulls the Postgres, Redis, and Ollama images, builds the Spring Boot jar inside a multi-stage Docker build, and you'll need to pull the LLM model separately:

```bash
docker exec -it upimesh-ollama ollama pull llama3
```

Then for the frontend:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. That's the entire setup — you don't need Java, Maven, Postgres, or Redis installed locally, just Docker and Node.

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

The sender encrypts the payload with the server's RSA-2048 public key. RSA can't directly encrypt anything much bigger than ~245 bytes, so I generate a fresh AES-256 key per packet, encrypt the actual JSON payload with AES-GCM, and only RSA-wrap the small AES key:

```
[256B RSA-OAEP-wrapped AES key][12B GCM IV][AES-GCM ciphertext + 16B auth tag]
```

**Why GCM specifically:** it's authenticated encryption. If someone flips one bit anywhere in transit, decryption just throws an exception — I don't need separate signature-checking code, tampering detection is basically a side effect of decryption succeeding or failing.

### Problem 2 — The duplicate storm

Say three strangers all happen to walk outside within the same second, all carrying the same packet. If you process all three naively, the sender gets debited three times instead of once.

**Solution: Redis `SETNX` on the ciphertext hash**, checked *before* any decryption happens:

```java
Boolean firstClaim = redis.opsForValue()
    .setIfAbsent(packetHash, timestamp, Duration.ofSeconds(ttl));
```

This is atomic at the Redis server level, so it holds even across multiple backend instances, not just multiple threads in one JVM. I hash the *ciphertext* specifically — not the outer `packetId`, which an intermediate device could rewrite, and not the decrypted plaintext, which would mean wasting CPU decrypting duplicates before rejecting them.

### Problem 3 — Replay attacks

An attacker who captured a ciphertext weeks ago could try replaying it later. There are two defenses here, both sealed inside the GCM-authenticated payload so neither can be forged without breaking the auth tag:

- `signedAt` — rejected if it's older than 24 hours.
- `nonce` — a fresh UUID per payment, so two legitimate identical-amount payments still produce different ciphertexts and settle independently, while a byte-identical replay collides on the idempotency hash and gets dropped.

---

## Why the mesh topology looks the way it does

Earlier versions of this simulator connected every device to every other one, which meant a packet could jump straight from the sender to the bridge node in a single hop. That's not really how a mesh network would behave — someone stuck in a basement can't magically reach the one person standing outside.

So I restricted the adjacency on purpose:

```java
"phone-alice",  → {stranger1, stranger2}          // no direct line to the bridge
"stranger1",    → {phone-alice, stranger2, bridge}
"stranger2",    → {phone-alice, stranger1, bridge}
"phone-bridge", → {stranger1, stranger2}          // no direct line to the sender
```

Now a payment needs an actual two-hop relay — sender → stranger → bridge — before it can settle. This is closer to what "offline, deferred settlement" is actually supposed to look like, and it means the hop count you see in the ledger (`hopCount: 2`) reflects something real instead of being an artifact of an accidentally fully-connected graph.

---

## The insights layer — and two times it lied to me

`InsightService` lets you ask natural-language questions about your spending, backed by a local Ollama/Llama 3 model — no cloud API involved, nothing leaves the machine. My rule going in was simple: Java computes every number, the LLM only narrates it in a sentence. The prompt tells the model not to do its own math.

I tested that rule on purpose, and it broke twice, which turned out to be more useful than if it had just worked.

**Case 1 — I asked for something that wasn't actually computed.** I asked for the average transaction size. At the time, `InsightContext` only carried totals and a transaction count — no average field. Instead of saying "I don't have that," the model just divided the numbers itself: ₹2375 ÷ 6, and got it wrong (₹396.17 instead of the correct ₹395.83). The fix was computing the average in Java with `BigDecimal` and `RoundingMode.HALF_UP`, plus adding an explicit instruction to the prompt — if a number isn't listed, say so instead of guessing.

**Case 2 — I asked for a per-counterparty breakdown that didn't exist yet.** "How much have I sent Bob?" `InsightContext` only had overall totals at that point, no per-recipient breakdown. The model grabbed a real number that happened to show up in the raw transaction list I'd handed it (a single ₹751 payment to Bob) and presented it as the answer — when the actual total sent to Bob across multiple payments was ₹1,875. This one's worse than Case 1, honestly — it's not bad arithmetic, it's a plausible-looking number stated as fact. The fix was computing a real `sentByCounterparty` / `receivedByCounterparty` breakdown in Java and handing that to the model directly, so there was nothing left for it to fill in on its own.

The pattern I kept running into: every time I caught the model guessing, the fix was never a stronger warning in the prompt — it was closing the actual data gap so there was nothing left to guess about. The current setup still means every new kind of question (spending by category, say, once categories exist) needs a new precomputed field added by hand. The real fix would be a tool-calling setup — the LLM asks for a specific query function by name and arguments, Java runs the actual query, and the model only narrates that one answer. That would let it handle arbitrary breakdowns without me touching the prompt every time. I'm leaving that as a known next step rather than building it out right now.

---

## Tech stack

| Layer | Technology | Why |
|---|---|---|
| Backend | Spring Boot 4.1, Java 17 | Full crypto/settlement pipeline |
| Database | PostgreSQL 16 (Docker) | Real persistence, survives restarts |
| Cache / idempotency | Redis 7 (Docker) | Atomic `SETNX`, TTL-based eviction, works across instances |
| Schema migrations | Flyway | Version-controlled schema, no `ddl-auto=create-drop` |
| Frontend | React 18 + Vite | Live mesh visualization, ledger, chat widget |
| Styling | Tailwind CSS 4 | Custom design tokens, not the default dark-dashboard look |
| Icons | lucide-react | Floating chat widget toggle |
| LLM | Ollama + Llama 3, local | No cloud API, nothing leaves the machine, no per-token cost |
| Boilerplate | Lombok | `@Getter`/`@Setter`/`@RequiredArgsConstructor` on services; explicit (non-`@Data`) annotations on JPA entities to avoid lazy-loading issues |
| Containerization | Docker Compose, multi-stage Dockerfile | One-command startup for the whole stack |

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
| POST | `/api/bridge/ingest` | The production endpoint — real bridges POST here |
| GET | `/api/insights/context?vpa=&days=` | Preview the computed numbers behind an insight answer |
| POST | `/api/insights/ask` | `{ vpa, question, days }` → LLM-narrated, Java-grounded answer |

---

## Running the demo, step by step

1. **Inject Payment** — simulates Alice's phone building, encrypting, and dropping a packet onto the mesh.
2. **Run Gossip Round** — click it twice. Because of the restricted topology, a packet needs an actual two-hop path (sender → stranger → bridge) to arrive.
3. **Flush Bridges** — the bridge node uploads everything it's holding to the real `/api/bridge/ingest` pipeline. Watch balances and the ledger update live.
4. **Ask the chat widget** (bottom-right) something like "How much have I sent Bob?" — grounded entirely in what Postgres actually has recorded.
5. **Reset Mesh** to clear state and the idempotency cache, and try again — try sending the same amount twice in a row to watch the idempotency guarantee hold up.

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

## Limitations

These aren't bugs so much as things that come with the territory of "no internet, anywhere in the chain":

1. **The receiver can't verify the sender actually has the funds at the moment of the handshake.** A shown "₹500 sent" is basically an IOU until the backend settles it — if the sender's account is empty by the time a bridge uploads the packet, the transaction gets `REJECTED` and the receiver has no recourse. This is part of why real offline UPI (UPI Lite) uses a pre-funded, hardware-backed wallet instead of trusting the sender's phone.
2. **A malicious sender could double-spend offline** — send the same balance to two different people in two different basements, and whichever packet reaches the backend first wins, the other gets rejected. Same root cause as #1.
3. **Real Bluetooth mesh networking is genuinely hard to build.** Background BLE is heavily throttled on modern Android, and iOS peripheral mode is locked down. This project sidesteps that with a software simulator instead of pretending it's a solved problem.
4. **Metadata leakage** — a stranger's phone can't read your payment, but the fact that a payment passed through their device at all is still information. A real deployment would need to think through what that means if a device is seized or subpoenaed.
5. **The insights layer only answers from a fixed set of precomputed aggregates**, not arbitrary questions — see the section above for why, and what fixing it properly would look like.

---

## License

Demo code, no license. Use it however you want for learning.
