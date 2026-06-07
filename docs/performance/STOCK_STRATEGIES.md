# Stock Strategies

This page is kept for compatibility with older links.

The current strategy and consistency documentation lives in:

- [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md)
- [REVIEWER_GUIDE.md](../reference/REVIEWER_GUIDE.md)
- [BENCHMARKING.md](./BENCHMARKING.md)

Current strategies:

| Strategy | Role |
|---|---|
| `UNSAFE_DB` | unsafe baseline that can oversell |
| `CONDITIONAL_DB` | DB conditional-update baseline |
| `REDIS_LUA` | Redis fast gate without compensation |
| `REDIS_LUA_WITH_COMPENSATION` | Redis fast gate with compensation and preferred lab framing |
