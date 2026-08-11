# CLAUDE.md

## 커밋 컨벤션

`<type>: <설명>` 형식을 따른다.

| type | 용도 |
|---|---|
| `feat` | 새 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 구조 개선 |
| `test` | 테스트 추가/수정 |
| `docs` | 문서 (README, 스펙 등) |
| `chore` | 설정, 빌드, gitignore 등 |

설명은 명령형으로 간결하게. 필요하면 빈 줄 후 본문에 "왜" 바꿨는지 적는다.

## PR 작성 컨벤션

- 제목: 커밋 컨벤션과 동일한 `<type>: <설명>` 형식
- 본문: `.github/PULL_REQUEST_TEMPLATE.md` 구조(Summary / Test plan)를 따르고, 한글로 작성
- 대응하는 GitHub 이슈가 있으면 `Closes #<번호>`로 연결
