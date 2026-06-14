import { sleep } from 'k6';
import { provisionUsers, updateProfile } from './common.js';

export const options = {
  scenarios: {
    saturation: {
      executor: 'constant-vus',
      vus: 50,
      duration: '1m',
      gracefulStop: '15s',
    },
  },
  thresholds: {
    'http_req_duration{name:update-profile}': ['p(95)<1000'],
    'http_req_failed{name:update-profile}': ['rate<0.05'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const tokens = provisionUsers(50);
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  updateProfile(token, `vu-${__VU}-iter-${__ITER}`);
  sleep(1);
}
