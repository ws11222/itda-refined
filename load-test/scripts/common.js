import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function authHeaders(token) {
  return { ...JSON_HEADERS, Authorization: `Bearer ${token}` };
}

export function signUpAndLogIn(email, password) {
  const signUpRes = http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password }),
    { headers: JSON_HEADERS, tags: { name: 'setup-signup' } },
  );
  if (signUpRes.status !== 200 && signUpRes.status !== 201) {
    fail(`signup failed: ${signUpRes.status} ${signUpRes.body}`);
  }
  const body = signUpRes.json();
  return body.accessToken;
}

export function provisionUsers(count) {
  const tokens = [];
  for (let i = 0; i < count; i++) {
    const ts = Date.now();
    const email = `loadtest-${ts}-${i}@example.com`;
    const password = 'password1234';
    tokens.push(signUpAndLogIn(email, password));
  }
  return tokens;
}

export function updateProfile(token, name) {
  const payload = JSON.stringify({
    name: name,
    birthDate: '1950-01-01',
    gender: 'MALE',
    address: 'Seoul',
    postcode: '03080',
    maritalStatus: 'MARRIED',
    educationLevel: 'HIGH_SCHOOL',
    householdSize: 2,
    householdIncome: 3000,
    employmentStatus: 'UNEMPLOYED',
    tags: ['건강', '복지'],
  });
  const res = http.put(`${BASE_URL}/api/v1/my-profile`, payload, {
    headers: authHeaders(token),
    tags: { name: 'update-profile' },
  });
  check(res, {
    'updateProfile status 200': (r) => r.status === 200,
  });
  return res;
}

export function getProfile(token) {
  const res = http.get(`${BASE_URL}/api/v1/my-profile`, {
    headers: authHeaders(token),
    tags: { name: 'get-profile' },
  });
  check(res, {
    'getProfile status 200': (r) => r.status === 200,
  });
  return res;
}
