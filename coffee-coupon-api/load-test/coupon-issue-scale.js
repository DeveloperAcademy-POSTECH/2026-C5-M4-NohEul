import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID;
const STRATEGY = __ENV.STRATEGY;
const VALID_STRATEGIES = ['no-lock', 'pessimistic', 'optimistic', 'distributed'];
const RATE = Number(__ENV.RATE || 500);
const DURATION = __ENV.DURATION || '10s';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 2000);
const MAX_VUS = Number(__ENV.MAX_VUS || 8000);

if (!CAMPAIGN_ID) {
  throw new Error('CAMPAIGN_ID env var is required. Usage: k6 run -e CAMPAIGN_ID=42 -e STRATEGY=pessimistic coupon-issue-scale.js');
}
if (!VALID_STRATEGIES.includes(STRATEGY)) {
  throw new Error(`STRATEGY env var must be one of ${VALID_STRATEGIES.join(', ')}, got: ${STRATEGY}`);
}

export const options = {
  scenarios: {
    coupon_open_burst: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
};

export default function () {
  const userId = exec.scenario.iterationInTest;
  const url = `${BASE_URL}/api/coupons/${CAMPAIGN_ID}/issue-${STRATEGY}`;
  const payload = JSON.stringify({ userId });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 200, 403, or 409': (r) => [200, 403, 409].includes(r.status),
  });
}
