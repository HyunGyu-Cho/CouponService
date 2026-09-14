#!/usr/bin/env bash
# 2층: 커밋 조건.
#   사용법 1 (git commit-msg 훅): check-commit.sh <메시지파일>   -> 스테이징된 파일 기준
#   사용법 2 (CI, 이미 만들어진 커밋): check-commit.sh --commit <sha>
# 조건:
#   - 첫 줄이 feat:/fix:/docs:/test:/refactor:/chore:/ci:/perf:/build: 로 시작
#   - 코드가 바뀌었는데 같은 커밋에 문서 변경이 없으면 본문에 "Docs:" 줄 필요
#   - 컴파일 성공 (커밋 시점에만, CI는 별도 job)
set -u
cd "$(git rev-parse --show-toplevel)"
source scripts/lib.sh

mode="staged"
if [ "${1:-}" = "--commit" ]; then
    mode="commit"; sha="$2"
    message="$(git log -1 --format=%B "$sha")"
    files="$(git show --format= --name-only "$sha")"
    label="커밋 $(git log -1 --format=%h "$sha")"
else
    msgfile="$1"
    message="$(grep -vE '^#' "$msgfile")"
    files="$(git diff --cached --name-only)"
    label="이번 커밋"
fi

subject="$(echo "$message" | sed -n '1p')"
errors=()

# 병합 커밋과 revert는 형식 검사 제외
if echo "$subject" | grep -qE '^(Merge|Revert) '; then
    exit 0
fi

if ! valid_subject "$subject"; then
    errors+=("첫 줄이 형식에 맞지 않습니다: \"$subject\"
    허용: feat|fix|docs|test|refactor|chore|ci|perf|build(범위): 내용
    예:   fix(v3): 중복 확인을 조건부 UPDATE보다 먼저 수행한다")
fi

code_changed="$(echo "$files" | filter_code)"
docs_changed="$(echo "$files" | filter_docs)"
if [ -n "$code_changed" ] && [ -z "$docs_changed" ] && ! has_docs_line "$message"; then
    errors+=("코드가 바뀌었는데 같은 커밋에 문서 변경이 없습니다.
    문서를 고쳤다면 함께 스테이징하고, 고칠 필요가 없다면 메시지 본문에 다음 줄을 추가하세요.
      Docs: 확인, 변경 없음 (이유)
    바뀐 코드:
$(echo "$code_changed" | sed 's/^/      - /')
    대응 문서:
$(echo "$code_changed" | docs_for_code | sed 's/^/      - /')")
fi

if [ "$mode" = "staged" ] && [ -n "$code_changed" ]; then
    gradle="$(gradle_cmd)"
    if ! "$gradle" -q compileJava compileTestJava >/tmp/check-commit-compile.log 2>&1; then
        errors+=("컴파일 실패:
$(tail -20 /tmp/check-commit-compile.log)")
    fi
fi

if [ "${#errors[@]}" -gt 0 ]; then
    echo "[커밋 거부] $label" >&2
    for e in "${errors[@]}"; do echo "  ✗ $e" >&2; done
    exit 1
fi
echo "[커밋 검사 통과] $label"
