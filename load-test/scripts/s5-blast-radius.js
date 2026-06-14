import { sleep } from 'k6';
import { provisionUsers, updateProfile, getProfile } from './common.js';

export const options = {
  scenarios: {
    profile_writers: {
      executor: 'constant-vus',
      exec: 'writer',
      vus: 10,
      duration: '1m',
      gracefulStop: '15s',
      startTime: '5s',
    },
    profile_readers: {
      executor: 'constant-vus',
      exec: 'reader',
      vus: 5,
      duration: '1m',
      gracefulStop: '15s',
      startTime: '5s',
    },
  },
  thresholds: {
    'http_req_duration{name:get-profile}': ['p(95)<500'],
    'http_req_failed{name:get-profile}': ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const writerTokens = provisionUsers(10);
  const readerTokens = provisionUsers(5);
  return { writerTokens, readerTokens };
}

export function writer(data) {
  const idx = (__VU - 1) % data.writerTokens.length;
  const token = data.writerTokens[idx];
  updateProfile(token, `vu-${__VU}-iter-${__ITER}`);
  sleep(1);
}

export function reader(data) {
  const idx = (__VU - 1) % data.readerTokens.length;
  const token = data.readerTokens[idx];
  getProfile(token);
  sleep(1);
}
