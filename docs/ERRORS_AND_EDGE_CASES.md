# Errors And Edge Cases

This page is kept for compatibility with older links.

Use [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) for current failure semantics, drift cases, reconciliation behavior, and troubleshooting commands.

Common result codes:

| Code | Meaning |
|---|---|
| `INVALID_REQUEST` | Request body or field validation failed |
| `REDIS_STOCK_UNAVAILABLE` | Redis stock is missing or insufficient |
| `DB_STOCK_DECREMENT_FAILED` | DB conditional stock update rejected |
| `ORDER_CREATE_FAILED` | Stock phase passed but order persistence failed |
| `WARMUP_FAILED` | Redis warmup could not read or set stock |
