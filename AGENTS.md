# CouponService Agent Instructions

이 파일은 저장소에서 작업할 때 가장 먼저 읽는 짧은 안내서다. 상세 규칙은 아래 문서를 따른다.
Claude Code는 [CLAUDE.md](CLAUDE.md)를 통해 이 파일로 진입한다. 규칙은 이 파일 한 곳에서만 관리한다.

## 먼저 읽을 문서

1. [README.md](README.md): 프로젝트 목적, 요구사항, 단계별 로드맵, **현재 진행 상태와 다음 작업**
2. [API 계약](docs/api.md): 요청·응답 JSON, 상태 코드, 오류 형식. 모든 구현 버전에 공통
3. [공통 개발 가이드](docs/development-guide.md): 계층 책임, 도메인, JPA, Validation, 트랜잭션 규칙. 모든 구현 버전에 공통
4. [Java 코드 컨벤션](docs/conventions/java.md): 명명, 메서드 책임, 변수, 주석 규칙
5. README의 "현재 진행 상태"가 가리키는 단계의 `docs/vN/development-guide.md`와 `load-test-result.md`

1~4번은 단계가 바뀌어도 그대로다. 단계마다 달라지는 것은 5번뿐이며, 어느 단계인지는 README의 "현재 진행 상태"만 정본으로 삼는다.
이 파일에는 현재 상태를 적지 않는다. 같은 사실을 두 곳에 적으면 한 곳은 반드시 낡는다.

API 또는 DTO를 변경할 때는 `docs/api.md`를 먼저 확인하고, 계약이 달라지면 문서를 코드보다 먼저 수정한다.

## 핵심 작업 규칙

- 직전 단계의 문제를 재현하기 전에는 다음 단계의 기술을 발급 흐름에 도입하지 않는다.
- 사용자가 직접 코드를 작성하는 학습 흐름에서는 완성 코드를 대신 작성하지 않는다. 원칙과 작은 힌트를 제공하고 작성된 코드를 검토한다.
- 한 번에 하나의 파일 또는 하나의 책임에 집중한다.
- 사용자가 요청하지 않으면 빌드와 테스트를 실행하지 않는다.
- 기존 사용자 변경사항을 보존하고 관련 없는 파일을 수정하지 않는다.
- Entity, Service, Repository, Controller, DTO의 책임을 섞지 않는다.
- 엔티티를 API 요청이나 응답에 직접 노출하지 않는다.
- 전체 Setter와 Lombok `@Data`를 엔티티에 사용하지 않는다.
- 비밀정보는 환경변수로 관리하고 `.env`를 읽어 출력하거나 커밋하지 않는다.

## 작업 종료 조건

"작업이 끝났다"는 세 순간으로 나뉘며, 각 순간에 `scripts/`의 검사가 자동으로 돈다.
검사는 잊는 것을 막는 장치이지 문서가 옳다고 보증하는 장치가 아니다. 읽고 판단하는 것은 작업자의 몫이다.

### 턴 종료 (`scripts/check-turn.sh`, Claude Code Stop 훅)

- 막음: 컴파일 실패, 테스트 실패(MariaDB가 켜져 있을 때), 깨진 문서 링크. 고칠 때까지 종료할 수 없다.
- 지시: 코드가 바뀌었으면 대응 문서를 **읽고, 어긋난 부분이 있으면 고친다.** 고칠 것이 없으면 답변에 판단을 한 줄 남긴다.
  예: "docs/v3 확인, 변경 없음: 파라미터 이름 오타라 설계와 무관"
- 코드와 문서의 대응은 다음과 같다.

| 바뀐 코드 | 읽을 문서 |
|---|---|
| `service/vN/` | `docs/vN/development-guide.md` |
| Controller, DTO | `docs/api.md` |
| Entity, Repository, global | `docs/development-guide.md` |
| 테스트, k6 스크립트 | 현재 단계의 `load-test-result.md` |
| 어떤 코드든 | README "현재 진행 상태", "다음 작업" |

### 커밋 (`scripts/check-commit.sh`, git commit-msg 훅과 CI)

- 첫 줄은 `feat|fix|docs|test|refactor|chore|ci|perf|build(범위): 내용` 형식이다. "수정"처럼 내용을 알 수 없는 메시지는 거부된다.
- 코드가 바뀌었는데 같은 커밋에 문서 변경이 없으면 본문에 `Docs:` 줄이 있어야 한다.
  예: `Docs: v3 확인, 변경 없음 (이름 오타라 문서 내용과 무관)`
  이 줄은 "문서를 읽고 판단했다"는 기록이며, 이력에 남아 나중에 근거가 된다.
- `--no-verify`로 검사를 건너뛰지 않는다.

### 단계 완료 (`scripts/check-stage.sh vN`, 태그 전 수동 실행과 CI)

한 단계(VN)는 아래를 모두 충족해야 완료다. 스크립트가 확인한다.

1. `docs/vN/development-guide.md`에 "문제", "가설", "변경", "결과", "판단" 제목이 있고 초안 표시가 없다
2. `docs/vN/load-test-result.md`가 있다
3. README "현재 진행 상태"에 VN 완료가 적혀 있고 `docs/vN` 링크가 있다
4. 테스트 전체 통과
5. 위를 통과한 뒤에만 `vN-<핵심기술>` 태그를 만든다

### 세션 시작 (`scripts/session-start.sh`, Claude Code SessionStart 훅)

- 세션 시작 시점 커밋을 기록해 턴 종료 검사의 기준점으로 삼는다.
- git 커밋 검사를 자동 설치한다 (`core.hooksPath=.githooks`).
- 지난 세션에서 커밋되지 않은 코드 변경이 남아 있으면, 작업 시작 전에 처리하라고 알린다.

## 문서와 코드가 다를 때

- 임의로 한쪽을 정답으로 가정하지 말고 차이를 사용자에게 알린다.
- 어느 쪽이 맞는지 정해진 뒤에 다른 쪽을 고친다.

## 문서 우선순위

- 사용자의 명시적인 요청
- 향후 추가될 OpenAPI 계약
- `docs/api.md`
- `docs/development-guide.md`
- 현재 단계의 `docs/vN/development-guide.md`
- `docs/conventions/java.md`
- `README.md`
