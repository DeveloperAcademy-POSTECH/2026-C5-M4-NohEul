# 브랜치 전략

Git Flow를 따릅니다.

| 브랜치 | 생명주기 | 분기 시작점 | 병합 대상 | 역할 |
|---|---|---|---|---|
| `main` | 영구 | - | - | 항상 배포 가능한 안정 버전. 커밋마다 릴리즈 태그(`v1.0.0` 등)를 남긴다 |
| `develop` | 영구 | `main` | - | 다음 릴리즈를 위한 통합 브랜치. 모든 기능이 여기로 모인다 |
| `feature/*` | 임시 | `develop` | `develop` | 개별 기능 개발. 완료되면 `develop`로 병합 후 삭제 |
| `release/*` | 임시 | `develop` | `main` + `develop` | 릴리즈 준비(버그 수정, 버전 정리 등). 새 기능 추가는 하지 않는다 |
| `hotfix/*` | 임시 | `main` | `main` + `develop` | 배포된 버전의 긴급 버그 수정 |

**작업 흐름**

1. `develop`에서 `feature/xxx` 분기 → 기능 개발 → `develop`로 머지
2. 릴리즈 준비가 되면 `develop`에서 `release/x.y.z` 분기 → 최종 점검 및 버그 수정
3. `release/x.y.z`를 `main`에 머지하고 태그(`vx.y.z`)를 남긴 뒤, `develop`에도 머지
4. 운영 중 긴급 버그 발견 시 `main`에서 `hotfix/xxx` 분기 → 수정 → `main`과 `develop` 양쪽에 머지

**브랜치 네이밍 예시**

- `feature/pessimistic-lock`
- `feature/optimistic-lock`
- `feature/distributed-lock`
- `release/0.1.0`
- `hotfix/coupon-overissue`
