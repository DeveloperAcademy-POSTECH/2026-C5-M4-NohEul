import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const CAMPAIGN_ID = __ENV.CAMPAIGN_ID;
const STRATEGY = __ENV.STRATEGY;
const VALID_STRATEGIES = ['no-lock', 'pessimistic', 'optimistic', 'synchronized', 'distributed'];
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

// run-all.sh가 --summary-export JSON에서 읽어 결과 표를 만든다. 수동 실행 동작은 그대로다.
const issued = new Counter('result_issued');            // 200: 발급 성공
const rejected = new Counter('result_rejected');        // 403/409: 락 로직에 의한 정상 거부
const infraError = new Counter('result_infra_error');   // 그 외: 5xx, 타임아웃 등 인프라 오류

export default function () {
  const userId = exec.scenario.iterationInTest;
  const url = `${BASE_URL}/api/coupons/${CAMPAIGN_ID}/issue-${STRATEGY}`;
  const payload = JSON.stringify({ userId });
  const params = { headers: { 'Content-Type': 'application/json' } };

  const res = http.post(url, payload, params);

  check(res, {
    'status is 200, 403, or 409': (r) => [200, 403, 409].includes(r.status),
  });

  if (res.status === 200) issued.add(1);
  else if (res.status === 403 || res.status === 409) rejected.add(1);
  else infraError.add(1);
}
