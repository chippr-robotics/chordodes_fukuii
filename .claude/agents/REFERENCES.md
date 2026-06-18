# Agent Reference Repositories

Reference repos are cloned locally at `<fukuii-root>/.claude/repo-references/` (gitignored — per-machine, not committed). This file documents which repos exist, their GitHub URLs, and which agents use them.

## Clone Convention

```bash
cd "$(git rev-parse --show-toplevel)/.claude/repo-references"

# Core Language
git clone https://github.com/scala/scala3.git
git clone https://github.com/scala/scala.git scala2
git clone https://github.com/scala/docs.scala-lang.git

# Actor Framework
git clone https://github.com/apache/pekko.git
git clone https://github.com/apache/pekko-connectors.git        # Batch 1
git clone https://github.com/apache/pekko-http.git              # Batch 1
mkdir -p virtuslab && cd virtuslab
git clone https://github.com/VirtusLab/pekko-serialization-helper.git
git clone https://github.com/VirtusLab/scala-skill.git
cd ..

# Testing
git clone https://github.com/scalamock/scalamock.git
mkdir -p typelevel && cd typelevel                               # Batch 3
git clone https://github.com/typelevel/cats.git
git clone https://github.com/typelevel/cats-effect.git
git clone https://github.com/typelevel/fs2.git
cd ..
git clone https://github.com/scalacenter/scalafix.git           # Batch 3
git clone https://github.com/sksamuel/scapegoat.git             # Batch 3

# Spec-Driven Development
git clone https://github.com/github/spec-kit.git

# Ethereum Protocol (ECIPs and EIPs may be copied from local canonical paths)
git clone https://github.com/ethereumclassic/ECIPs.git          # NOTE: local copy may be ahead of upstream (see note below)
git clone https://github.com/ethereum/EIPs.git
mkdir -p ethereum && cd ethereum                                 # Batch 4
git clone https://github.com/ethereum/devp2p.git                # Batch 1 (devp2p spec)
git clone https://github.com/ethereum/execution-apis.git        # Batch 2
git clone https://github.com/ethereum/yellowpaper.git           # Batch 4
git clone https://github.com/ethereum/consensus-specs.git       # Batch 4
git clone https://github.com/ethereum/tests.git                 # Batch 4
cd ..

# JSON / Serialization (Batch 2 — CONDUIT work)
git clone https://github.com/json4s/json4s.git
git clone https://github.com/circe/circe.git
git clone https://github.com/sangria-graphql/sangria.git
```

> **ECIPs local-ahead note:** The local `repo-references/ECIPs` copy contains Olympia spec
> changes (ECIP-1111/1112/1121/1122) that have not been published upstream yet. This is
> intentional — we are the lead core developers on ETC and the authors of these ECIPs.
> The local copy is authoritative. Do **not** `git pull` to replace it with the public
> upstream without checking which branch you are on. The working spec is at `_specs/`.

## Sync All Clones

```bash
REFS=$(git rev-parse --show-toplevel)/.claude/repo-references
find "$REFS" -maxdepth 3 -name .git -exec dirname {} \; \
  | xargs -I{} git -C {} pull --ff-only 2>/dev/null
```

---

## Repository Index

### Core Language — Scala 3

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala3 |
| **Clone as** | `repo-references/scala3` |
| **Used by** | `mithril`, `wraith`, `prism` |
| **Key paths** | `AGENTS.md` · `changelogs/` · `docs/docs/reference/` · `tests/` |
| **Why** | Canonical Scala 3 idioms, breaking-change log, `// error` test annotation conventions, new migration patterns as they land |

### Core Language — Scala 2 (migration source reference)

| | |
|---|---|
| **GitHub** | https://github.com/scala/scala |
| **Clone as** | `repo-references/scala2` |
| **Used by** | `wraith` |
| **Key paths** | `AGENTS.md` · `src/library/` |
| **Why** | Recognise Scala 2 stdlib patterns during migration; understand what AGENTS.md says is safe to modify |

### Core Language — Scala Documentation

| | |
|---|---|
| **GitHub** | https://github.com/scala/docs.scala-lang |
| **Clone as** | `repo-references/docs.scala-lang` |
| **Used by** | `mithril` |
| **Key paths** | `_overviews/scala3-migration/` · `_scala3-reference/` · `_overviews/scala3-book/` |
| **Why** | Migration cookbook, idiomatic Scala 3 examples, official style guidance |

---

### Actor Framework — Apache Pekko

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko |
| **Clone as** | `repo-references/pekko` |
| **Used by** | `loom`, `herald` |
| **Key paths** | `AGENTS.md` · `actor-typed/src/main/scala/` · `CHANGELOG.md` · `serialization/` |
| **Why** | Canonical Typed actor API patterns; MiMa binary-compat rules; formatting and licensing rules from `AGENTS.md`; serialization marker interfaces |

### Actor Framework — Pekko Serialization Helper (VirtusLab)

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/pekko-serialization-helper |
| **Clone as** | `repo-references/virtuslab/pekko-serialization-helper` |
| **Used by** | `loom` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | `@SerializabilityTrait` and companion annotations — **required reading before migrating any actor that crosses a cluster/network boundary** |

---

### Testing — ScalaMock

| | |
|---|---|
| **GitHub** | https://github.com/scalamock/scalamock |
| **Clone as** | `repo-references/scalamock` |
| **Used by** | `prism`, `eye` |
| **Key paths** | `README.md` · `core/src/main/scala/` |
| **Why** | Idiomatic Scala 3 mock patterns; verify fukuii tests use current API |

---

### Spec-Driven Development — Spec Kit

| | |
|---|---|
| **GitHub** | https://github.com/github/spec-kit |
| **Clone as** | `repo-references/spec-kit` |
| **Used by** | all `speckit-*` skills |
| **Key paths** | `AGENTS.md` · `CHANGELOG.md` · `templates/` · `docs/` · `extensions/` |
| **Why** | Upstream SDD workflow — new spec templates, integration patterns, skill updates. Check `CHANGELOG.md` before starting a `speckit-specify` or `speckit-plan` session |

---

### VirtusLab — Scala Skill

| | |
|---|---|
| **GitHub** | https://github.com/VirtusLab/scala-skill |
| **Clone as** | `repo-references/virtuslab/scala-skill` |
| **Used by** | `mithril` |
| **Key paths** | `README.md` |
| **Why** | IDE-integrated Scala development patterns; cross-reference when proposing editor-visible refactors |

---

### Ethereum Protocol — ECIPs

| | |
|---|---|
| **GitHub** | https://github.com/ethereumclassic/ECIPs |
| **Clone as** | `repo-references/ECIPs` |
| **Used by** | `forge`, `herald`, `beacon` |
| **Key paths** | `_specs/` — all ECIP markdown specs |
| **Why** | Authoritative ETC fork schedule and specification text. **Local copy may be ahead of upstream** — we are the authors of Olympia (ECIP-1111/1112/1121/1122) and drafts not yet published publicly live here. Always prefer the local copy over the public URL. Check `_specs/ecip-1111.md`, `_specs/ecip-1112.md`, `_specs/ecip-1121.md`, `_specs/ecip-1122.md` for Olympia. |

### Ethereum Protocol — EIPs

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/EIPs |
| **Clone as** | `repo-references/EIPs` |
| **Used by** | `beacon`, `forge`, `herald`, `conduit` |
| **Key paths** | `EIPS/` — EIP markdown specs |
| **Why** | Canonical ETH EIP text for Osaka (EIP-7706, EIP-7939, etc.) and all cross-referenced EIPs in Olympia ECIPs. Use `EIPS/eip-NNNN.md` — no network fetch needed. |

---

### Ethereum P2P — devp2p Spec (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/devp2p |
| **Clone as** | `repo-references/ethereum/devp2p` |
| **Used by** | `herald` |
| **Key paths** | `rlpx.md` · `discv4.md` · `discv5/` · `eth/67.md` · `eth/68.md` · `eth/69.md` · `snap.md` |
| **Why** | Wire-level protocol specs for RLPx, discovery v4/v5, ETH68/69/70 message formats, SNAP. Read before any herald change touching handshake, message encoding, or fork ID. |

### Actor Framework — Pekko Connectors (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko-connectors |
| **Clone as** | `repo-references/pekko-connectors` |
| **Used by** | `loom`, `conduit` |
| **Key paths** | `*.md` · source module connectors |
| **Why** | Pekko-idiomatic streaming connector patterns; reference when migrating TCP/IPC/network actors that use Pekko Streams |

### Actor Framework — Pekko HTTP (Batch 1)

| | |
|---|---|
| **GitHub** | https://github.com/apache/pekko-http |
| **Clone as** | `repo-references/pekko-http` |
| **Used by** | `conduit` |
| **Key paths** | `http-core/` · `http/` · `docs/` |
| **Why** | HTTP/WebSocket routing DSL used in JsonRpcHttpServer and JsonRpcWebsocketServer — check for idiomatic route definition and streaming patterns |

---

### JSON-RPC API Spec — execution-apis (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/execution-apis |
| **Clone as** | `repo-references/ethereum/execution-apis` |
| **Used by** | `conduit` |
| **Key paths** | `api-documentation/` · `openrpc.json` |
| **Why** | Authoritative ETH execution layer JSON-RPC spec (eth_*, net_*, web3_*). Cross-reference before implementing or fixing any JSON-RPC method. |

### JSON / Serialization — json4s (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/json4s/json4s |
| **Clone as** | `repo-references/json4s` |
| **Used by** | `conduit` |
| **Key paths** | `core/src/` · `native/src/` · `README.md` |
| **Why** | fukuii uses json4s for JSON-RPC serialization; reference when fixing codec bugs or migrating to circe |

### JSON / Serialization — circe (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/circe/circe |
| **Clone as** | `repo-references/circe` |
| **Used by** | `conduit` |
| **Key paths** | `modules/core/` · `modules/parser/` · `README.md` |
| **Why** | Target library if json4s is replaced; reference for idiomatic Scala 3 JSON codec patterns |

### GraphQL — Sangria (Batch 2)

| | |
|---|---|
| **GitHub** | https://github.com/sangria-graphql/sangria |
| **Clone as** | `repo-references/sangria` |
| **Used by** | `conduit` |
| **Key paths** | `src/main/scala/sangria/` · `README.md` |
| **Why** | GraphQL schema and execution library used in `jsonrpc/graphql/GraphQLSchema.scala`; reference for schema definition DSL and resolver patterns |

---

### Scala Tooling — Scalafix (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/scalacenter/scalafix |
| **Clone as** | `repo-references/scalafix` |
| **Used by** | `mithril`, `wraith` |
| **Key paths** | `docs/` · `rules/src/main/scala/scalafix/` |
| **Why** | Custom rule development; understanding what built-in rules (GivenUsing, ExplicitImplicitTypes) transform; debugging `.scalafix.conf` failures |

### Scala Tooling — Scapegoat (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/sksamuel/scapegoat |
| **Clone as** | `repo-references/scapegoat` |
| **Used by** | `prism`, `wraith` |
| **Key paths** | `src/main/scala/com/sksamuel/scapegoat/inspections/` · `README.md` |
| **Why** | Understanding which inspections are enabled/disabled in `build.sbt`; reference for false-positive patterns before suppressing a warning |

### Typelevel — Cats (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/cats |
| **Clone as** | `repo-references/typelevel/cats` |
| **Used by** | `mithril` |
| **Key paths** | `core/src/main/scala/cats/` · `docs/` |
| **Why** | Idiomatic functional abstractions (Functor, Monad, Traverse) used in sync pipeline; reference when refactoring to cats-style |

### Typelevel — Cats Effect (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/cats-effect |
| **Clone as** | `repo-references/typelevel/cats-effect` |
| **Used by** | `mithril` |
| **Key paths** | `core/src/main/scala/cats/effect/` · `docs/` |
| **Why** | IO monad, Resource, Fiber patterns — reference if sync/network actors are ever migrated from Pekko to cats-effect-based concurrency |

### Typelevel — fs2 (Batch 3)

| | |
|---|---|
| **GitHub** | https://github.com/typelevel/fs2 |
| **Clone as** | `repo-references/typelevel/fs2` |
| **Used by** | `mithril`, `herald` |
| **Key paths** | `core/src/main/scala/fs2/` · `docs/` |
| **Why** | Streaming alternative to Pekko Streams; reference if network IO is ever migrated from Pekko to fs2 |

---

### Ethereum Specs — Yellowpaper (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/yellowpaper |
| **Clone as** | `repo-references/ethereum/yellowpaper` |
| **Used by** | `forge`, `beacon` |
| **Key paths** | `Paper.pdf` · `Paper.tex` |
| **Why** | Formal EVM and transaction processing spec; last resort when EIP text is ambiguous |

### Ethereum Specs — Consensus Specs (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/consensus-specs |
| **Clone as** | `repo-references/ethereum/consensus-specs` |
| **Used by** | `beacon` |
| **Key paths** | `specs/phase0/` · `specs/bellatrix/` · `specs/capella/` · `specs/deneb/` |
| **Why** | PoS consensus layer spec — beacon block processing, withdrawals, execution payload format |

### Ethereum Specs — Test Vectors (Batch 4)

| | |
|---|---|
| **GitHub** | https://github.com/ethereum/tests |
| **Clone as** | `repo-references/ethereum/tests` |
| **Used by** | `forge`, `beacon`, `eye` |
| **Key paths** | `GeneralStateTests/` · `BlockchainTests/` · `VMTests/` |
| **Why** | Canonical state test vectors; cross-reference when EVM opcode or gas cost behavior is in question |
