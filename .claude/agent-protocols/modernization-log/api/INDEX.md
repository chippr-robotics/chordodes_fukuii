# api/ — API Surface

**Packages:** `jsonrpc/` (~79 files, HTTP/IPC/GraphQL), `transactions/`
**Gate:** `conduit` on JSON-RPC; no gate on transaction types

| File | Package | Key Changes |
|------|---------|-------------|
| [jsonrpc.md](jsonrpc.md) | `jsonrpc/` | W2-P2b Typed migration; H1-A/H1-B memory leak fixes; A1/B1 IO/threading audit; serverSocket lifecycle (S3-G) |
| [transactions.md](transactions.md) | `transactions/` | W2-P2c Typed migration; RLP codec `given` syntax |
