# storage/ — State Persistence

**Packages:** `db/` (RocksDB, ~50 files), `ledger/` (tx execution context, receipts)
**Gate:** `vault` on RocksDB/DataSource changes; `forge` on ledger/receipt semantics

| File | Package | Key Changes |
|------|---------|-------------|
| [db.md](db.md) | `db/` | W2-P1 wildcard migration; M3 FileUtils resource leak fix |
| [ledger.md](ledger.md) | `ledger/` | W2-P1 wildcard migration; forge gate on receipt logic |
