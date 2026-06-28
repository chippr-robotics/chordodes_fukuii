# network/messages — Wire Protocol Message Codecs

**Package:** `network/p2p/messages/` (`ETHPackets.scala`, `SNAP.scala`, `Capability.scala`, …)
**Gate:** `herald` on all encoding/decoding changes

---

## Property-Based Round-Trip Test Coverage (8h, 2026-06-25)

**Commits:** `9932d3b86`, `f2659102a` — branch `scala3-cleanup-june`

Added `forAll { msg => decode(encode(msg)) == msg }` coverage for all ETH68/69/70 and SNAP/1 message types. Prior to this, ETHPackets and SNAP codecs had unit tests with fixed examples only; no ScalaCheck property coverage existed for the wire protocol layer.

### ETHPacketsRoundTripSpec — 18 tests (all PASS)

| Message type | Notes |
|-------------|-------|
| `Status68/69/70` | `ForkId(hash: BigInt, next: Option[BigInt])` generated with full `none` / `some` frequency |
| `NewBlockHashes` | List of `(hash32, blockNum)` pairs; empty list included |
| `GetBlockHeaders (Left)` | By block number — `blockNumGen` produces BigInt encodable in ≤8 bytes (< 32), matching decoder arm |
| `GetBlockHeaders (Right)` | By block hash — `hash32Gen` produces exactly 32 bytes, never < 32 |
| `BlockHeaders` | Empty `Seq.empty` only — full `BlockHeader` `RLPList` reference-equality constraint |
| `GetBlockBodies`, `GetPooledTransactions`, `GetReceipts/69/70` | Hash list + requestId |
| `NewPooledTransactionHashes` | `require(types.length == sizes.length == hashes.length)` — equal-length lists generated with shared `n` |
| `BlockBodies` | Empty Seq — same `RLPList` constraint as `BlockHeaders` |
| `BlockRangeUpdate` | ETH69/EIP-7642: `(earliest, latest, latestHash)` — no requestId |
| `Receipts68/69` | Assert `requestId` only — `receiptsForBlocks: RLPList` uses Array reference equality |
| `Receipts70` | Assert `requestId` + `lastBlockIncomplete` — `RLPList()` body avoids equality issue |

**Key finding:** `RLPList` wraps `Seq[RLPEncodeable]` where `RLPValue` contains `Array[Byte]`. Scala arrays use reference equality, so encode→decode of a non-empty `RLPList` produces a structurally equal but referentially distinct value. Tests use `RLPList()` and assert on scalar fields only. This is not a correctness bug — the encode/decode path is correct; it's an assertion strategy constraint.

### SNAPRoundTripSpec — 8 tests (all PASS)

| Message type | Notes |
|-------------|-------|
| `GetAccountRange` | `boundaryHashGen` exercises `0x00…00` and `0xff…ff` at 2/5 frequency each |
| `AccountRange` | Slim-format encoding via `SnapServer.toSlimAccountRlp`: `EmptyStorageRootHash`/`EmptyCodeHash` → empty bytes on encode; decoder normalizes back. Full `shouldBe` works because `Account(nonce, balance)` defaults survive the round-trip losslessly |
| `GetStorageRanges` | Variable `accountHashes` list; boundary hashes for `startingHash`/`limitHash` |
| `StorageRanges` | Nested `Seq[Seq[(ByteString, ByteString)]]` slots + proof nodes |
| `GetByteCodes` | Empty and non-empty `hashes` list |
| `ByteCodes` | Variable-length bytecode list (`randomSizeByteStringGen(0, 512)`) |
| `GetTrieNodes` | Nested path lists (`Seq[Seq[ByteString]]`) |
| `TrieNodes` | Variable-length node list |
