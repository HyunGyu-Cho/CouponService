#!/usr/bin/env bash
# 모든 md 파일의 상대 링크가 실제 파일을 가리키는지 확인한다.
set -u
cd "$(git rev-parse --show-toplevel)"

broken=0
while IFS=: read -r file line link; do
    target="${link#](}"
    target="${target%%#*}"
    dir="$(dirname "$file")"
    if [ ! -e "$dir/$target" ]; then
        echo "깨진 링크: $file:$line -> $target"
        broken=$((broken + 1))
    fi
done < <(grep -rnoE '\]\(([^)#:]+\.md)' --include='*.md' . 2>/dev/null | grep -vE '^\./(build|\.gradle|node_modules)/')

if [ "$broken" -gt 0 ]; then
    exit 1
fi
echo "문서 링크 정상"
