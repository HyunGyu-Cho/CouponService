import http from 'k6/http'; // k6에서 HTTP 요청을 보내는 기능
import exec from 'k6/execution'; // 현재 실행 중인 가상 사용자와 반복 횟수 등의 정보 가져옴
import { Counter } from 'k6/metrics'; // 특정 사건이 몇 번 발생했는지 직접 세는 기능

const createdCount = new Counter('coupon_issue_created'); // 정상처리
const conflictCount = new Counter('coupon_issue_conflict'); // 충돌
const unexpectedCount = new Counter('coupon_issue_unexpected'); // 예측 불가

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const couponId = __ENV.COUPON_ID;

// 쿠폰Id 전달하지 않았다면 즉시 중단
if (!couponId) {
    throw new Error('COUPON_ID 환경변수가 필요합니다.');
}

export const options = {
    discardResponseBodies: true, // 응답 본문을 메모리에 보관하지 않도록 한다

    scenarios: {
        issueCoupon: {
            executor: 'per-vu-iterations', // 각 vu가 지정된 횟수만큼 실행되게 한다
            vus: 1000, // 가상 사용자 수 1000명
            iterations: 1, // 사용자별 요청 1회
            maxDuration: '30s', // 30초 안에 완료되지 않으면 테스트 종료
        },
    },
};

// k6의 가상 사용자가 실행할 함수. 현재 설정에서는 1000명의 vu가 함수를 각각 1번 실행
export default function () {
    const userId = exec.vu.idInTest; // 테스트 전체에서 각 VU에게 고유한 번호를 부여한다
    const url = `${baseUrl}/api/coupons/${couponId}/issue`;

    // 요청 본문
    const payload = JSON.stringify({
        userId: userId,
    });

    // 요청 헤더
    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };

    const response = http.post(url, payload, params);

    if (response.status === 201) {
        createdCount.add(1);
    } else if (response.status === 409) {
        conflictCount.add(1);
    } else {
        unexpectedCount.add(1);
    }
}