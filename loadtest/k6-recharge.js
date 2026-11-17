// Load test for the recharge flow. Run against a demo-profile instance:
//   k6 run -e BASE_URL=http://localhost:8080 loadtest/k6-recharge.js
// Note: the default rate limits are per user, so every virtual user gets its own demo identity.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 100 },   // payday spike
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_order}': ['p(95)<300'],
    'http_req_duration{name:plans}': ['p(95)<150'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const json = { 'Content-Type': 'application/json' };

export default function () {
  const token = http.post(`${BASE}/v1/demo/token`, '{}', { headers: json }).json('accessToken');
  const auth = { ...json, Authorization: `Bearer ${token}` };

  const plans = http.get(`${BASE}/v1/plans?market=DE`, { tags: { name: 'plans' } });
  const planId = plans.json('plans.0.id');

  const msisdn = `+49170${Math.floor(1000000 + Math.random() * 8999999)}`;
  const number = http.post(`${BASE}/v1/numbers`, JSON.stringify({ msisdn }), { headers: auth });
  const numberId = number.json('id');

  const order = http.post(`${BASE}/v1/orders`, JSON.stringify({ numberId, planId }), {
    headers: { ...auth, 'Idempotency-Key': uuidv4().replace(/-/g, '') },
    tags: { name: 'create_order' },
  });
  check(order, { 'order created': (r) => r.status === 201 });

  const pay = http.post(`${BASE}/v1/orders/${order.json('id')}/pay`, JSON.stringify({ paymentToken: 'tok_success' }), {
    headers: auth,
    tags: { name: 'pay' },
  });
  check(pay, { 'payment accepted': (r) => r.status === 200 });
  sleep(1);
}
