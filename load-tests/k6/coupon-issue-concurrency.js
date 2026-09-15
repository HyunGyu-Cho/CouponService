import http from 'k6/http';
import exec from 'k6/execution';
import { classifyIssueResponse } from './lib/issue-response.js';

// 순간 부하: VU 전원이 동시에 한 번씩 요청한다. 정합성과 HTTP 진입 한계를 본다.
// 지속 부하(DB 행 경합 재현)는 coupon-issue-sustained.js 를 쓴다.

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID;
const vus = Number(__ENV.VUS || 1000);
const maxDuration = __ENV.MAX_DURATION || '30s';

if (!couponId) {
    throw new Error('COUPON_ID 환경변수가 필요합니다. 예: k6 run -e COUPON_ID=1 -e VUS=100 ...');
}

if (!Number.isInteger(vus) || vus <= 0) {
    throw new Error('VUS 는 1 이상의 정수여야 합니다. 받은 값: ' + __ENV.VUS);
}

export const options = {
    // 오류 코드를 읽어야 하므로 응답 본문을 버리지 않는다
    discardResponseBodies: false,

    scenarios: {
        issueCoupon: {
            // per-vu-iterations 를 유지해 각 VU 가 고유한 userId 로 정확히 한 번 요청하게 한다.
            // CLI --vus 로 덮어쓰면 shared-iterations 로 바뀌므로 VU 수는 -e VUS= 로 전달한다.
            executor: 'per-vu-iterations',
            vus: vus,
            iterations: 1,
            maxDuration: maxDuration,
        },
    },
};

export default function () {
    const userId = exec.vu.idInTest; // 테스트 전체에서 VU 마다 고유
    const url = `${baseUrl}/api/coupons/${couponId}/issue`;
    const payload = JSON.stringify({ userId: userId });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const response = http.post(url, payload, params);
    classifyIssueResponse(response, userId);
}
