#!/usr/bin/env bash
# 3층: 단계 종료 조건. 태그를 만들기 전에 실행한다.
#   사용법: check-stage.sh v3
set -u
cd "$(git rev-parse --show-toplevel)"
source scripts/lib.sh

v="${1:-}"
if ! echo "$v" | grep -qE '^v[0-9]+$'; then
    echo "사용법: scripts/check-stage.sh vN  (예: v3)" >&2; exit 1
fi
V="$(echo "$v" | tr 'a-z' 'A-Z')"
guide="docs/$v/development-guide.md"
result="docs/$v/load-test-result.md"
ok=0; ng=0
pass() { echo "  ✓ $1"; ok=$((ok+1)); }
failc() { echo "  ✗ $1"; ng=$((ng+1)); }

echo "[단계 검사] $v"

[ -f "$guide" ] && pass "$guide 존재" || failc "$guide 없음"
if [ -f "$guide" ]; then
    for h in 문제 가설 변경 결과 판단; do
        grep -qE "^## .*$h" "$guide" && pass "$guide 에 \"## $h\" 제목" || failc "$guide 에 \"## $h\" 제목 없음"
    done
    grep -qiE '상태:.*(초안|미검증)' "$guide" && failc "$guide 가 아직 초안/미검증 상태로 표시됨" || pass "$guide 상태 표시 정리됨"
fi

[ -f "$result" ] && pass "$result 존재" || failc "$result 없음"

if grep -qE "docs/$v/" README.md; then pass "README 에 docs/$v 링크"; else failc "README 에 docs/$v 링크 없음"; fi
if awk '/^## 현재 진행 상태/{f=1} /^## 다음 작업/{f=0} f' README.md | grep -E "$V" | grep -v '미검증' | grep -qE '완료'; then
    pass "README 현재 진행 상태에 $V 완료 언급"
else
    failc "README 현재 진행 상태에 $V 완료 언급 없음"
fi

gradle="$(gradle_cmd)"
if db_reachable || [ "${CI:-}" = "true" ]; then
    if "$gradle" -q test >/tmp/check-stage-test.log 2>&1; then pass "테스트 전체 통과"; else failc "테스트 실패 (/tmp/check-stage-test.log)"; fi
else
    failc "MariaDB(3307)가 꺼져 있어 테스트를 돌리지 못함. 단계 완료 선언에는 테스트가 필요합니다"
fi

echo
if [ "$ng" -gt 0 ]; then
    echo "[단계 미완료] $v: $ng 건 미충족. 위 항목을 채운 뒤 태그를 만드세요." >&2
    exit 1
fi
echo "[단계 완료 조건 충족] $v. 이제 태그를 만들 수 있습니다: git tag -a $v-<핵심기술> -m \"...\""
