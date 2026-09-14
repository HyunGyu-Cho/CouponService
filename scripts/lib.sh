#!/usr/bin/env bash
# 검사 스크립트 공통 함수. 직접 실행하지 않고 다른 스크립트에서 source 한다.

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
STATE_DIR="$REPO_ROOT/.claude/state"
SESSION_HEAD_FILE="$STATE_DIR/session-head"

# Windows(Git Bash)면 gradlew.bat, 아니면 gradlew
gradle_cmd() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) echo "$REPO_ROOT/gradlew.bat" ;;
        *) echo "$REPO_ROOT/gradlew" ;;
    esac
}

# MariaDB가 열려 있는지 (application.properties 기본 포트 3307)
db_reachable() {
    timeout 2 bash -c 'echo > /dev/tcp/127.0.0.1/3307' 2>/dev/null
}

# 세션 시작 시점 커밋. 없으면 HEAD.
session_base() {
    if [ -f "$SESSION_HEAD_FILE" ] && git cat-file -e "$(cat "$SESSION_HEAD_FILE")" 2>/dev/null; then
        cat "$SESSION_HEAD_FILE"
    else
        git rev-parse HEAD
    fi
}

# 기준 커밋 이후 바뀐 파일 전체 (커밋된 것 + 미커밋 + 새 파일)
changed_since() {
    local base="$1"
    {
        git diff --name-only "$base" 2>/dev/null
        git ls-files --others --exclude-standard
    } | sort -u
}

# 파일 목록(stdin)에서 코드 파일만
filter_code() {
    grep -E '^(src/|load-tests/|build\.gradle$|settings\.gradle$)' || true
}

# 파일 목록(stdin)에서 문서 파일만
filter_docs() {
    grep -E '^(docs/.*\.md$|README\.md$|AGENTS\.md$)' || true
}

# 코드 파일 목록(stdin) -> 읽어야 할 문서 목록
docs_for_code() {
    while IFS= read -r f; do
        [ -z "$f" ] && continue
        case "$f" in
            src/main/java/*/service/v[0-9]*/*)
                v="$(echo "$f" | sed -E 's#.*/service/(v[0-9]+)/.*#\1#')"
                echo "docs/$v/development-guide.md"
                ;;
            src/main/java/*/controller/*|src/main/java/*/dto/*)
                echo "docs/api.md"
                ;;
            src/main/*)
                echo "docs/development-guide.md"
                ;;
            src/test/*|load-tests/*)
                echo "docs/<현재 단계>/load-test-result.md"
                ;;
        esac
        echo "README.md (현재 진행 상태, 다음 작업)"
    done | sort -u
}

# 커밋 메시지 첫 줄 형식
valid_subject() {
    echo "$1" | grep -qE '^(feat|fix|docs|test|refactor|chore|ci|perf|build)(\([a-z0-9,/-]+\))?: .+'
}

# 커밋 메시지 본문에 Docs: 줄이 있는지
has_docs_line() {
    echo "$1" | grep -qE '^Docs:'
}
