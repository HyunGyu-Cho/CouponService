import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

// 응답을 원인별로 나누어 센다. 하나의 counter에 섞으면 나중에 원인을 되짚을 수 없다 (V2 실험의 교훈).
const createdCount = new Counter('coupon_issue_created'); // 201 Created
const soldOutCount = new Counter('coupon_issue_sold_out'); // 409 COUPON_007
const duplicateCount = new Counter('coupon_issue_duplicate'); // 409 COUPON_010
const conflictOtherCount = new Counter('coupon_issue_conflict_other'); // 409 그 외 (기간 전, 만료)
const serverErrorCount = new Counter('coupon_issue_server_error'); // 5xx
const networkErrorCount = new Counter('coupon_issue_network_error'); // status 0, 연결 거절·타임아웃
const unexpectedCount = new Counter('coupon_issue_unexpected'); // 위 어디에도 속하지 않는 응답

const SOLD_OUT_CODE = 'COUPON_007';
const DUPLICATE_ISSUE_CODE = 'COUPON_010';

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
    classify(response, userId);
}

function classify(response, userId) {
    const status = response.status;

    if (status === 201) {
        createdCount.add(1);
        return;
    }

    if (status === 0) {
        networkErrorCount.add(1);
        console.warn(`network error userId=${userId} error=${response.error} error_code=${response.error_code}`);
        return;
    }

    if (status === 409) {
        const code = readErrorCode(response);
        if (code === SOLD_OUT_CODE) {
            soldOutCount.add(1);
        } else if (code === DUPLICATE_ISSUE_CODE) {
            duplicateCount.add(1);
        } else {
            conflictOtherCount.add(1);
            console.warn(`409 other userId=${userId} code=${code}`);
        }
        return;
    }

    if (status >= 500) {
        serverErrorCount.add(1);
        console.warn(`server error userId=${userId} status=${status} code=${readErrorCode(response)}`);
        return;
    }

    unexpectedCount.add(1);
    console.warn(`unexpected userId=${userId} status=${status} code=${readErrorCode(response)}`);
}

function readErrorCode(response) {
    try {
        const body = response.json();
        return body && body.code ? body.code : '(no code)';
    } catch (e) {
        return '(unreadable body)';
    }
}
