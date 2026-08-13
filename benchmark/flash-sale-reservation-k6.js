import http from 'k6/http';
import { check } from 'k6';
import { randomUUID } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const baseUrl = __ENV.BASE_URL || 'http://localhost:1122';
const ticketItemId = Number(__ENV.TICKET_ITEM_ID || 4);

export const options = {
  vus: Number(__ENV.VUS || 100),
  iterations: Number(__ENV.ITERATIONS || 5000),
  thresholds: {
    http_req_failed: ['rate==0'],
  },
  tags: {
    flashsale_synthetic: 'true',
    workload: 'reservation',
  },
};

export default function () {
  const actorId = randomUUID();
  const idempotencyKey = randomUUID();
  const traceId = randomUUID().replaceAll('-', '');
  const traceparent = `00-${traceId}-${randomUUID().replaceAll('-', '').slice(0, 16)}-01`;
  const response = http.post(
    `${baseUrl}/api/v1/reservations`,
    JSON.stringify({ ticketItemId, quantity: [1, 1, 1, 2, 2, 3, 4][__VU % 7] }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        'X-Demo-Actor-Id': actorId,
        'traceparent': traceparent,
        'X-Flashsale-Synthetic': 'true',
      },
      tags: { operation: 'reservation.create' },
      responseCallback: http.expectedStatuses(201, 202, 409, 429, 503),
    },
  );

  check(response, {
    'reservation response is bounded': (result) => [201, 202, 409, 429, 503].includes(result.status),
  });
}
