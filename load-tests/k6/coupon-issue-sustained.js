import http from 'k6/http';
import exec from 'k6/execution';
import { classifyIssueResponse } from './lib/issue-response.js';

// 지속 부하: 정해진 도착률(req/s)로 일정 시간 계속 요청한다.
// 순간 연결 폭발을 피하고 DB 행 잠금 앞에 줄이 서는 상황(V4 문제 재현)을 만든다.
// 재고는 실험 동안 매진되지 않을 만큼 크게 두어야 모든 요청이 실제로 UPDATE 를 수행한다.

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID;
const rate = Number(__ENV.RATE || 100); // 초당 요청 수
const duration = __ENV.DURATION || '30s';
const preAllocatedVus = Number(__ENV.PRE_ALLOCATED_VUS || Math.min(rate * 2, 500));
const maxVus = Number(__ENV.MAX_VUS || Math.min(rate * 4, 900)); // 1,000 미만: 로컬 HTTP 진입 한계 회피
const userIdOffset = Number(__ENV.USER_ID_OFFSET || 0); // 같은 쿠폰으로 재실행할 때 이전 사용자와 겹치지 않게

if (!couponId) {
    throw new Error('COUPON_ID 환경변수가 필요합니다. 예: k6 run -e COUPON_ID=1 -e RATE=200 -e DURATION=30s ...');
}

if (!Number.isInteger(rate) || rate <= 0) {
    throw new Error('RATE 는 1 이상의 정수여야 합니다. 받은 값: ' + __ENV.RATE);
}

export const options = {
    discardResponseBodies: false,

    scenarios: {
        sustainedIssue: {
            executor: 'constant-arrival-rate',
            rate: rate,
            timeUnit: '1s',
            duration: duration,
            preAllocatedVUs: preAllocatedVus,
            maxVUs: maxVus,
        },
    },
};

export default function () {
    // 시나리오 전체에서 iteration 번호는 VU 와 무관하게 고유하다. 1부터 시작하도록 1을 더한다.
    const userId = userIdOffset + exec.scenario.iterationInTest + 1;
    const url = `${baseUrl}/api/coupons/${couponId}/issue`;
    const payload = JSON.stringify({ userId: userId });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const response = http.post(url, payload, params);
    classifyIssueResponse(response, userId);
}
