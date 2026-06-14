import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { BASE_URL, provisionUsers } from './common.js';

const TOTAL_ITERATIONS = 300;

export const options = {
  scenarios: {
    cache_miss: {
      executor: 'shared-iterations',
      vus: 10,
      iterations: TOTAL_ITERATIONS,
      maxDuration: '3m',
    },
  },
  thresholds: {
    'http_req_duration{name:feed-miss}': ['p(95)<500'],
    'http_req_failed{name:feed-miss}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const tokens = provisionUsers(TOTAL_ITERATIONS);
  return { tokens };
}

export default function (data) {
  const idx = exec.scenario.iterationInTest;
  const token = data.tokens[idx];
  const res = http.get(`${BASE_URL}/api/v1/programs?page=0&size=10`, {
    headers: { Authorization: `Bearer ${token}` },
    tags: { name: 'feed-miss' },
  });
  check(res, {
    'feed status 200': (r) => r.status === 200,
  });
}
