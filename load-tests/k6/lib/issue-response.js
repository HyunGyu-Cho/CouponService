import { Counter } from 'k6/metrics';

// 발급 응답을 원인별로 나누어 센다. 순간 부하와 지속 부하 스크립트가 같은 집계 이름을 쓴다.
const createdCount = new Counter('coupon_issue_created'); // 201 Created
const soldOutCount = new Counter('coupon_issue_sold_out'); // 409 COUPON_007
const duplicateCount = new Counter('coupon_issue_duplicate'); // 409 COUPON_010
const conflictOtherCount = new Counter('coupon_issue_conflict_other'); // 409 그 외 (기간 전, 만료)
const serverErrorCount = new Counter('coupon_issue_server_error'); // 5xx
const networkErrorCount = new Counter('coupon_issue_network_error'); // status 0, 연결 거절·타임아웃
const unexpectedCount = new Counter('coupon_issue_unexpected'); // 위 어디에도 속하지 않는 응답

const SOLD_OUT_CODE = 'COUPON_007';
const DUPLICATE_ISSUE_CODE = 'COUPON_010';

export function classifyIssueResponse(response, userId) {
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
