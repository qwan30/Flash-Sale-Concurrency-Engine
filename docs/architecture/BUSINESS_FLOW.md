# Business Flow

This page is kept for compatibility with older links.

Use the current docs instead:

- [REVIEWER_GUIDE.md](./REVIEWER_GUIDE.md) for the project story and reviewer walkthrough.
- [BENCHMARKING.md](./BENCHMARKING.md) for the reset, warmup, benchmark, and consistency workflow.
- [CONCURRENCY_AND_CONSISTENCY.md](./CONCURRENCY_AND_CONSISTENCY.md) for stock strategy behavior and correctness interpretation.

Current core cycle:

```text
Reset fixture -> Warm Redis -> Submit orders -> Check consistency -> Save benchmark evidence
```
