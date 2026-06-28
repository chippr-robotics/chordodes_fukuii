# consensus/ — PoW/PoS Engine, Validators, VM

**Packages:** `consensus/pow/`, `mining/`, `consensus/engine/`, `consensus/validators/`, `eip1559/`, `mess/`, `vm/`
**Gate:** `forge` (ETC — PoW, ECIP) / `beacon` (ETH — PoS, timestamp forks) on ALL changes

CRITICAL: Any change here requires forge or beacon pre-flight before implementation.

| File | Package | Key Changes |
|------|---------|-------------|
| [pow.md](pow.md) | `consensus/pow/` + `mining/` | W2-P2d mining Typed migration, forge gate |
| [engine.md](engine.md) | `consensus/engine/` | EngineApiService memory/IO audit (S3-A, S3-D, S3-F), beacon gate |
| [validators.md](validators.md) | `consensus/validators/` + `eip1559/` + `mess/` | W2-P1 wildcard migration, forge/beacon gate |
| [vm.md](vm.md) | `vm/` | Opcode dispatch, gas metering, forge/beacon gate |
