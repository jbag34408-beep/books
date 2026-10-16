# 이어서 할 작업

마지막 작업: 2026-10-16 · 설계·API·리뷰 전체는 [BACKEND.md](BACKEND.md)

## 현재 상태

- `backend/`: Spring Boot 4.1.1 + PostgreSQL + Flyway 백엔드 완성, 테스트 통과 (`.\mvnw test`, PostgreSQL 설치 없이 동작)
- **아직 안 한 것**: 이 PC에 PostgreSQL 설치 → 실제 서버 실행 확인

## 1. PostgreSQL 설치 (EDB 설치 프로그램)

다운로드: https://www.postgresql.org/download/windows/ (17 이상)

**Select Components 화면**

| 항목 | 선택 | 이유 |
|---|---|---|
| PostgreSQL Server | ✅ | DB 본체 (필수) |
| Command Line Tools | ✅ | `psql` 포함. 계정·DB 생성에 필요 |
| pgAdmin 4 | ✅ (권장) | 테이블·데이터를 보는 GUI |
| Stack Builder | ❌ | 이 프로젝트에 불필요 |

**나머지 화면**

- Data Directory: 기본값
- Password: 슈퍼유저 `postgres` 비밀번호. **꼭 기억해 둘 것**
- Port: `5432` (백엔드 기본 설정과 같음)
- Locale: `Default locale` (UTF-8, 한글 정상 저장)
- 마지막 "Launch Stack Builder at exit?" → 체크 해제 후 Finish

## 2. 계정·DB 만들기

시작 메뉴 → **SQL Shell (psql)** → Server/Database/Port/Username은 엔터로 기본값 → `postgres` 비밀번호 입력 후:

```sql
create user movie with password '원하는-비밀번호';
create database movie owner movie;
```

## 3. 백엔드 실행

```powershell
cd C:\Users\COMPUTER\Desktop\books\backend
$env:DB_PASSWORD = '원하는-비밀번호'
.\mvnw spring-boot:run
```

확인: 브라우저에서 http://localhost:8080/api/movies 를 열어 영화 15편이 JSON으로 나오면 성공입니다. 테이블과 샘플 데이터는 첫 실행 때 Flyway가 자동으로 만듭니다.

자주 나는 오류:

- `password authentication failed` → `DB_PASSWORD`가 2단계에서 정한 비밀번호와 다름
- `Connection refused` → PostgreSQL 서비스가 꺼져 있음 (`services.msc`에서 `postgresql-x64-…` 시작)
- `DB_PASSWORD` 관련 placeholder 오류 → 환경변수를 설정하지 않았음 (PowerShell 창마다 다시 설정해야 함)

## 4. 그다음 (BACKEND.md 리뷰 이슈 순서)

1. JWT 로그인 + 로그인/가입 rate limit (이슈 1, 3). 공개 배포 전 필수
2. TMDB API로 실제 영화 데이터 가져오기 (이슈 9, 10). TMDB API 키 필요
3. 프론트엔드 연결 (Vite 기본 주소 `http://localhost:5173`이 CORS에 허용되어 있음)
