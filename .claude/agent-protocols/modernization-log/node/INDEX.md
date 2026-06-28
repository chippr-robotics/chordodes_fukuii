# node/ — Node Bootstrap and Support

**Packages:** `nodebuilder/`, `cli/`, `runtime/`, `forkid/`, `healthcheck/`, `metrics/`, `faucet/`
**Gate:** None (infrastructure, not consensus)

| File | Package | Key Changes |
|------|---------|-------------|
| [bootstrap.md](bootstrap.md) | `nodebuilder/` + `faucet/` | W2-P2a faucet Typed; H2/H3 StdNode teardown; CAPSTONE ROOT ActorSystem[Nothing] |
| [testing-infra.md](testing-infra.md) | `src/test/` utilities | TestKit migration (8a batches 1-2); ETH test coverage G1-G5 |
