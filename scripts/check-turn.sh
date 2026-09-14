#!/usr/bin/env bash
# 1층: 턴 종료 조건.
# Claude Code Stop 훅에서 실행된다. 실패하면 exit 2와 stderr 메시지로 종료를 막는다.
#   막음: 컴파일, 테스트(DB가 켜져 있을 때), 문서 링크
#   지시: 코드가 바뀌었으면 대응 문서를 읽고 판단하라 (첫 번째 종료 시도에서만)
set -u
cd "$(git rev-parse --show-toplevel)"
source scripts/lib.sh

input="$(cat)"
stop_hook_active=false
echo "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true' && stop_hook_active=true

base="$(session_base)"
changed="$(changed_since "$base")"
code_changed="$(echo "$changed" | filter_code)"

fail() { echo "$1" >&2; exit 2; }

# --- 막음 1: 컴파일과 테스트 (코드가 바뀐 경우에만) ---
if [ -n "$code_changed" ]; then
    gradle="$(gradle_cmd)"
    if ! "$gradle" -q compileJava compileTestJava >/tmp/check-turn-compile.log 2>&1; then
        fail "[턴 종료 보류] 컴파일 실패. 고친 뒤 다시 종료하세요.
$(tail -30 /tmp/check-turn-compile.log)"
    fi
    if db_reachable; then
        if ! "$gradle" -q test >/tmp/check-turn-test.log 2>&1; then
            fail "[턴 종료 보류] 테스트 실패. 고친 뒤 다시 종료하세요.
$(grep -E 'FAILED|Exception|expected|but was' /tmp/check-turn-test.log | head -30)"
        fi
    else
        echo "[경고] MariaDB(3307)가 꺼져 있어 테스트를 건너뛰었습니다. 컴파일만 확인했습니다." >&2
    fi
fi

# --- 막음 2: 문서 링크 ---
if ! link_out="$(bash scripts/check-links.sh 2>&1)"; then
    fail "[턴 종료 보류] 문서 링크가 깨졌습니다.
$link_out"
fi

# --- 지시: 코드가 바뀌었으면 문서를 읽고 판단 ---
if [ -n "$code_changed" ] && [ "$stop_hook_active" = false ]; then
    docs="$(echo "$code_changed" | docs_for_code)"
    fail "[턴 종료 보류] 이번 세션에서 코드가 바뀌었습니다.

바뀐 코드:
$(echo "$code_changed" | sed 's/^/  - /')

다음 문서를 읽고, 바뀐 코드와 어긋난 부분이 있으면 실제 내용으로 고치세요.
$(echo "$docs" | sed 's/^/  - /')

고칠 것이 없다면 답변에 한 줄로 판단을 남기세요. 예: \"docs/v3 확인, 변경 없음: 파라미터 이름 오타라 설계와 무관\"
판단을 마쳤으면 다시 종료하면 됩니다. 이 지시는 한 번만 나옵니다."
fi

exit 0
