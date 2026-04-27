let idempotencySequence = 0;

export function nextIdempotencyKey(userId: number) {
  idempotencySequence += 1;
  return `ui-${userId}-${idempotencySequence}`;
}
