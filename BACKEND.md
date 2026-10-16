# 영화 추천 웹사이트 — 백엔드 설계 · 기술스택 · 코드리뷰

작성일: 2026-10-16 · 대상 코드: `backend/`

## 1. 벤치마킹: 보편적인 영화 추천 사이트

| 사이트 | 핵심 기능 | 이 프로젝트에 가져온 것 |
|---|---|---|
| 왓챠피디아 | 0.5점 단위 별점, 별점 기반 취향 분석·예상 별점, 보고싶어요, 코멘트 | 0.5~5.0 별점, 취향 기반 추천, 보고싶어요 |
| Letterboxd | 본 영화 기록(log), 별점+리뷰, 리스트, 팔로우 | 별점과 리뷰를 한 레코드로 저장(로그 방식) |
| IMDb | 가장 큰 영화 DB, 평균 평점·투표 수 | 영화 상세의 평균 별점·평가 수 |
| TMDB | 커뮤니티 편집 DB, 무료 API (많은 서비스의 데이터 소스) | 향후 영화 데이터 수집원 (현재는 샘플 데이터) |
| JustWatch | 어디서 볼 수 있는지(OTT) 검색, 워치리스트 | 워치리스트 (OTT 정보는 제외) |
| Trakt | 시청 기록 기반 추천 | 시청 기록 기반 추천 |

**공통분모 = MVP 범위**: 영화 검색/상세 → 회원가입 → 별점·리뷰 → 보고싶어요 → 개인화 추천.
**의도적으로 제외**: 팔로우/피드, 커스텀 리스트, 배우·감독 페이지, OTT 제공처, 댓글·좋아요, 관리자 CRUD.

## 2. 기술스택

| 영역 | 선택 | 이유 |
|---|---|---|
| 언어 | Java 17 (Temurin) | 설치된 JDK, Spring Boot 4의 최소 요구 버전 |
| 프레임워크 | Spring Boot 4.1.1 (Spring MVC) | 자바 웹 백엔드의 사실상 표준 |
| 영속성 | Spring Data JPA + Hibernate 7 | 리포지토리 인터페이스+JPQL만으로 CRUD·페이징·추천 쿼리 해결 |
| DB | PostgreSQL (18로 테스트) | 오픈소스 RDBMS 중 표준. 대부분의 클라우드(RDS, Supabase, Neon 등)에서 지원 |
| 마이그레이션 | Flyway 12 (`db/migration`) | 스키마를 SQL 파일로 버전 관리. Hibernate는 `ddl-auto: validate`로 검증만 |
| 인증 | Spring Security, HTTP Basic + BCrypt, stateless | 코드가 가장 적은 안전한 인증. 추후 JWT/세션 전환 |
| 검증 | Bean Validation (jakarta.validation) | 요청 DTO에 어노테이션으로 입력 검증 |
| JSON | Jackson 3 (Boot 4 기본) | 별도 설정 없음 |
| 빌드 | Maven 3.9.16 + Maven Wrapper (`mvnw`) | Maven 미설치 PC에서도 빌드 가능 |
| 테스트 | JUnit 5 + `@SpringBootTest` + JDK `HttpClient` + zonky embedded-postgres | 실제 서버와 실제 PostgreSQL(설치·Docker 불필요)로 API 흐름 전체를 검증 |

추가 라이브러리(Lombok, MapStruct, QueryDSL, JWT 등)는 쓰지 않았습니다.

## 3. 설계

### 3.1 구조

```
backend/
├─ pom.xml, mvnw, mvnw.cmd
└─ src/main/java/com/example/movie/
   ├─ MovieApplication.java   진입점
   ├─ Entities.java           Movie, AppUser, Rating, WatchlistItem
   ├─ Repositories.java       JPA 리포지토리 + 검색/인기/추천 JPQL + 응답 record
   ├─ Controllers.java        Auth / Movie / Me 컨트롤러 + 요청 DTO
   └─ SecurityConfig.java     인증·인가·CORS
   resources/application.yml
   resources/db/migration/V1__schema.sql        테이블·인덱스·제약조건
   resources/db/migration/V2__seed_movies.sql   샘플 영화 15편
```

로직이 쿼리에 거의 다 있어서 Service 계층은 두지 않았습니다. 컨트롤러가 리포지토리를 직접 호출하고, 트랜잭션은 쓰기 메서드의 `@Transactional`이 담당합니다. 비즈니스 규칙이 늘어나면 그때 Service로 분리하면 됩니다.

### 3.2 ERD

```
app_user (id, username UQ, password_hash)
   │1                        │1
   │N                        │N
rating (id, user_id, movie_id, score, review, updated_at)   UQ(user_id, movie_id)
watchlist_item (id, user_id, movie_id, added_at)            UQ(user_id, movie_id)
   │N                        │N
   │1                        │1
movie (id, title, release_year, overview, poster_url) ──1:N── movie_genre (movie_id, genre)
```

### 3.3 API

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/api/auth/signup` | – | 회원가입 `{username, password}` → 201 / 409 중복 / 400 |
| GET | `/api/movies?q=&genre=&page=&size=&sort=` | – | 검색·장르 필터·페이징·정렬 (`sort=releaseYear,desc`) |
| GET | `/api/movies/popular?size=` | – | 평가 수 → 평균 별점 순 |
| GET | `/api/movies/{id}` | – | 상세 + `avgScore`, `ratingCount` |
| GET | `/api/movies/{id}/ratings` | – | 해당 영화의 별점·리뷰 목록 |
| PUT | `/api/movies/{id}/rating` | ✔ | 별점/리뷰 등록·수정(upsert) `{score: 0.5~5.0, review?}` |
| DELETE | `/api/movies/{id}/rating` | ✔ | 내 별점 삭제 |
| GET | `/api/me/ratings` | ✔ | 내가 평가한 영화 |
| GET | `/api/me/recommendations?size=` | ✔ | 개인화 추천 |
| GET/PUT/DELETE | `/api/me/watchlist[/{movieId}]` | ✔ | 보고싶어요 조회·추가(멱등)·삭제 |

인증 방식은 `Authorization: Basic base64(username:password)` 헤더입니다. 로그인 확인용 엔드포인트는 따로 없고, 아무 인증 API(예: `GET /api/me/ratings`)가 200을 주면 로그인 성공입니다.

### 3.4 추천 알고리즘

아이템 기반 협업 필터링을 JPQL 쿼리 하나로 구현했습니다 (`MovieRepository.recommendedFor`).

1. 내가 4점 이상 준 영화 A
2. A에 4점 이상 준 다른 사용자들("취향이 비슷한 사람")
3. 그 사람들이 4점 이상 준 영화 중 내가 아직 평가하지 않은 영화
4. 겹친 횟수 → 평균 별점 순으로 정렬

추천 결과가 비면(콜드 스타트: 신규 회원 등) 내가 평가하지 않은 인기 영화로 대체합니다.

### 3.5 실행

1. PostgreSQL을 설치합니다 ([공식 설치 파일](https://www.postgresql.org/download/windows/), 17 이상).
2. DB와 계정을 만듭니다 (`psql -U postgres`에서 실행).
   ```sql
   create user movie with password '원하는-비밀번호';
   create database movie owner movie;
   ```
3. 실행합니다. 테이블과 샘플 데이터는 첫 실행 때 Flyway가 만듭니다.
   ```powershell
   cd backend
   $env:DB_PASSWORD = '원하는-비밀번호'   # 선택: $env:DB_URL, $env:DB_USERNAME (기본값 localhost:5432/movie, movie)
   .\mvnw spring-boot:run              # http://localhost:8080
   ```

테스트(`./mvnw test`)는 PostgreSQL 바이너리를 직접 띄우므로 설치 없이 돌아갑니다.

스키마를 바꿀 때는 기존 파일을 고치지 말고 `V3__설명.sql`처럼 새 파일을 추가하세요. 이미 적용된 마이그레이션을 수정하면 Flyway가 체크섬 불일치로 실행을 막습니다.

프론트엔드 개발 서버 주소는 `application.yml`의 `app.cors-origin`(기본값 `http://localhost:5173`, Vite)에서 설정합니다.

## 4. 코드리뷰 (셀프 리뷰)

### 검증한 것

`ApiTest`에서 실제 서버를 띄워 다음을 확인했습니다. **통과 (1 test, 0 failures).**

- 한글 시드 데이터 인코딩, 장르 필터, 정렬
- 회원가입 201 / 중복 409 / 잘못된 입력 400
- 비로그인 쓰기 401, 별점 4.3·5.5 → 400, 없는 영화 → 404
- 별점 upsert, 평균·개수 집계, 리뷰 응답에 `passwordHash`가 노출되지 않는지
- 협업 필터링 추천 결과, 콜드 스타트 시 인기순 대체
- 워치리스트 멱등 추가와 삭제

### 리뷰 중 수정한 것

- **N+1 쿼리**: 영화 목록 20개 조회 시 장르를 영화마다 따로 SELECT해서 쿼리가 21번 나갔습니다. `@BatchSize(100)`를 붙여 2번으로 줄였습니다.
- **H2 → PostgreSQL 교체 (2026-10-16)**: H2에서는 통과하던 검색 쿼리가 PostgreSQL에서 `function lower(bytea) does not exist`로 실패했습니다. 검색어가 없을 때 null 파라미터의 타입을 PostgreSQL이 추론하지 못하기 때문입니다. null이 될 수 있는 파라미터에 `cast(:q as String)`을 붙여 해결했고, 테스트를 실제 PostgreSQL로 돌리도록 바꿨습니다.
- **인덱스**: PostgreSQL은 FK에 인덱스를 자동으로 만들지 않아서 `rating(movie_id)`와 `movie_genre(movie_id, genre)`에 인덱스를 추가했습니다. 별점 범위(0.5~5.0)는 DB `check` 제약으로도 막았습니다.

### 남은 이슈 (우선순위순)

| # | 심각도 | 위치 | 문제 | 해결 시점·방법 |
|---|---|---|---|---|
| 1 | 높음 | `SecurityConfig` | HTTP Basic은 매 요청마다 비밀번호를 보내고, 서버는 매 요청마다 BCrypt 검증(약 50~100ms CPU)을 합니다. HTTPS 없이는 비밀번호가 평문 노출되고, 트래픽이 늘면 CPU가 병목이 됩니다. | 공개 배포 전에 JWT 또는 세션 쿠키로 전환하고 HTTPS 적용 |
| ~~2~~ | 해결 | `application.yml` | ~~H2 인메모리 DB라 재시작하면 데이터가 사라짐~~ → PostgreSQL + Flyway로 교체 | – |
| 3 | 중간 | `SecurityConfig` | 회원가입·로그인 시도에 횟수 제한이 없어 무차별 대입 공격에 노출됩니다. | 공개 전에 IP 기준 rate limit(Bucket4j나 리버스 프록시) 적용 |
| 4 | 중간 | `recommendedFor` | `rating` 테이블을 3번 self-join하므로 평가 수가 수십만 건을 넘으면 느려집니다. (`movie_id` 인덱스는 추가함) | 데이터가 커지면 영화 간 유사도를 배치로 미리 계산해 테이블에 저장 |
| 10 | 낮음 | `V2__seed_movies.sql` | 장르 INSERT가 영화 id 1~15를 하드코딩해서 빈 DB에서만 맞습니다. | TMDB import를 만들 때 이 파일을 대체 |
| 11 | 낮음 | 검색 | `title like '%검색어%'`는 인덱스를 못 탑니다. 수만 건까지는 문제없습니다. | 느려지면 `pg_trgm` GIN 인덱스 추가 |
| 5 | 낮음 | `rate`, `addToWatchlist` | 같은 사용자가 같은 영화에 동시에 두 번 요청하면 UNIQUE 제약 위반으로 500이 납니다. 데이터는 깨지지 않습니다. | 프론트에서 버튼 연타를 막으면 충분하고, 필요하면 409로 변환 |
| 6 | 낮음 | `popular` | 평가 수를 우선으로 정렬해서 평가 1개짜리 5점 영화와 많이 평가된 영화가 공정하게 비교되지 않습니다. | 데이터가 쌓이면 베이지안 평균(IMDb 방식)으로 변경 |
| 7 | 낮음 | 전역 | 400 응답에 어떤 필드가 잘못됐는지가 포함되지 않습니다 (Spring 기본 에러 JSON). | 프론트에서 필드별 메시지가 필요해지면 `@RestControllerAdvice`로 `ProblemDetail` 반환 |
| 8 | 낮음 | `Movie` | 엔티티를 그대로 JSON으로 응답해서 DB 스키마와 API가 묶여 있습니다. (`AppUser`·`Rating`은 DTO로 분리해 민감정보 노출 없음) | 스키마와 API가 달라져야 할 때 `MovieResponse` record로 분리 |
| 9 | 정보 | `data.sql` | 영화 데이터가 손으로 넣은 15편뿐입니다. | TMDB API로 가져오는 import 작업 추가 (API 키 필요) |

### 다음 단계 제안

1. JWT 로그인 (이슈 1) + rate limit (이슈 3)
2. TMDB 데이터 import (이슈 9). 포스터 URL도 함께 채워짐
3. 프론트엔드 (React/Vite 등) 연결

## 출처

- [AlternativeTo — TMDB alternatives](https://alternativeto.net/software/the-open-movie-database)
- [AlternativeTo — JustWatch alternatives](https://www.alternativeto.net/software/justwatch/)
- [AlternativeTo — Rotten Tomatoes alternatives](https://www.alternativeto.net/software/rotten-tomatoes/)
- [Ahrefs — letterboxd.com competitors](https://ahrefs.com/websites/letterboxd.com/competitors)
- [나무위키 — 왓챠피디아](https://namu.moe/w/%EC%99%93%EC%B1%A0%ED%94%BC%EB%94%94%EC%95%84)
- [요즘IT — 왓챠피디아](https://yozm.wishket.com/magazine/detail/791)
- [velog — 왓챠피디아의 UX](https://velog.io/@stop_yes/왓챠피디아의-UX)
- [뉴스토마토 — 나도 모르는 내 영화 취향, 앱이 알려준다](https://newstomato.com/ReadNews.aspx?no=928176)
