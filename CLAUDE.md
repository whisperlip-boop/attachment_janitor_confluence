# Attachment Janitor — 개발 메모

이 파일은 이 저장소에서 작업할 때 필요한 배경이다. 사용자용 설명은 [README.md](README.md),
환경 실측과 기획서와 달라진 판단은 [docs/00-환경실측.md](docs/00-환경실측.md)에 있다.

## 목표

"첨부 용량이 어디로 갔나"에 답하는 재료를 모아 보여준다. **v1은 조회 전용이다.**
삭제 기능을 넣지 않는 것은 의도다 — 형제 앱 Custom Field Janitor 와 같은 원칙이고,
앱 이름·메뉴 라벨에 "Cleanup"/"Delete"를 쓰지 않는 이유도 같다.

기획서 11장의 2~8단계가 들어 있다. 화면 넷(스페이스 랭킹 · 스페이스 상세 · 중복 · 설정)과
사용 설명서, 한영 토글(`?lang=ko|en`), 참조 스캐너, 라벨 5종, 배지 4종, 필터, CSV.
**변경 동작(삭제 · 구버전 정리 · 이동)은 일절 없다** — 넣을지 여부는 별도 논의 대상이다.

## 환경

- 빌드: Atlassian Plugin SDK (`atlas-mvn`), JDK 8
- 컴파일 대상: confluence **7.8.1** (운영 타깃은 7.12.3). 낮게 컴파일해 높게 돌린다.
- 테스트 인스턴스: docker Confluence 7.8.1 + PostgreSQL
- 오프라인 빌드 가능(`atlas-mvn -o`). 단 최초 1회는 온라인이 필요하다.

**인스턴스 주소·계정은 저장소에 적지 않는다.** 아래 세 환경변수로 넘긴다.
스크립트도 전부 이 규약을 따른다.

```bash
export CONFLUENCE_BASE=http://<host>:<port>
export CONFLUENCE_USER=<admin>
export CONFLUENCE_PASS='...'
```

DB·로그를 직접 볼 때는 컨테이너 이름만 자기 환경 값으로 바꿔 쓴다:

```bash
docker exec <db-container> psql -U <user> -d confluence -c "SELECT COUNT(*) FROM content;"
docker exec <confluence-container> \
    tail -100 /var/atlassian/application-data/confluence/logs/atlassian-confluence.log
```

## 구조

```
co.bskim.confluence.attachjanitor
├── ao/        AjScanRun · AjSpaceStat · AjAttachment · AjRefHit · AjDupGroup
├── model/     Label · Badge · RefKind (열거형이 판정·화면·설명서의 공통 어휘다)
│              AttachmentFacts · RefSource · AttachmentRef · ScanOutcome · ScanProgress
├── analyze/   ReferenceScanner(본문→참조) · HtmlEntities · ReferenceIndex(참조→첨부)
│              Judge(라벨·배지 판정이 있는 유일한 곳) · DuplicateFinder(2단계)
├── scan/      ScanService(5단계 · 잠금 · 실패 보관) · BodyWalker(본문 순회)
├── store/     ScanStore (AO를 만지는 유일한 곳 · 보관 정책)
├── settings/  Settings · SettingsStore (Bandana)
├── rest/      JanitorResource · Json · JsonReader
├── servlet/   JanitorServlet(라우팅·껍데기·웹수도) · HelpPage(설명서)
└── web/       AccessGuard · LocaleText(+Factory) · Screen · StaticAssets
```

새 라벨/배지/참조 종류를 추가하려면 **열거형에 값 하나 + i18n 키**가 전부다. 설명서 표,
화면 필터, CSV 가 전부 열거형을 순회한다. `BundleKeysTest` 가 키 누락을 빌드에서 잡는다.
필터 목록은 서버가 `data-labels` · `data-badges` 로 실어 보내고 JS 가 그대로 쓴다 —
**JS 에 코드를 다시 적지 않는다.** 적으면 열거형에 값을 더해도 필터에서만 조용히 빠지고,
그건 테스트가 못 잡는다.

## 절대 바꾸지 말 것 (기획서와 실측의 근거)

1. **plugin key `co.bskim.confluence.attachment-janitor` 는 바꾸지 않는다.**
   표시 이름은 언제든 바꿀 수 있다.
2. **스캔은 인스턴스 1회 순회다.** `AttachmentDao` **인터페이스**에는 스페이스 단위
   조회가 없다(실측 V3). 스페이스별 버튼을 만들면 같은 전체 스트림을 돌고 대부분을
   버리게 된다. 단 Hibernate 구현체에는 `findLatestVersionIdsIterator(List<Space>)` 가
   있다(실측 8번) — 인터페이스 밖이라 v1 에서는 쓰지 않지만, 대형 인스턴스에서
   트랜잭션을 쪼개야 할 때 이 사실이 출발점이다.
3. **최신 버전 판정은 `PREVVER IS NULL` 이다.** 구버전 행이 *머리* 를 가리킨다(실측 V1).
   체인을 거슬러 올라가는 코드를 쓰면 조용히 틀린다.
4. **구버전 조회는 `getVersion() > 1` 일 때만 한다.** 전 첨부에 부르면 첨부 수만큼
   쿼리가 늘어난다. 다만 **이 필터가 이 앱의 대상 인스턴스에서는 잘 걸러 주지 않는다** —
   호출 1회가 약 14ms 라서, 같은 2,044개 첨부가 구버전 17개일 때 1.2초, 1,048개일 때
   16~20초였다(실측 4번). "스캔은 빠르다"고 적지 말 것. 개선하려면 4·8번을 함께 읽는다.
5. **파일시스템을 읽지 않는다.** DB 메타(`FILESIZE`)가 디스크 크기와 일치한다(실측 V2).
   권한·경로·DC 공유 홈 문제를 통째로 피하는 결정이다.
6. **셀 수 없는 것을 칸으로 만들지 않는다.** "삭제됐지만 디스크에 남은 첨부"는
   `findLatestVersionsIterator()` 가 돌려주지 않아 정직하게 셀 수 없다(실측 2번).
   구조적으로 항상 0 인 칸은 "없다"로 읽히므로 아예 빼고 한계 문구로 적었다.
7. **실패를 조용히 누락시키지 않는다.** 읽지 못한 첨부는 `skippedCount` 로 세어 화면
   배너로 낸다. 실패·취소도 행으로 저장한다. 관리자가 몇 주 전 스냅샷을 방금 것으로
   믿게 만드는 것이 이 도구에서 가장 나쁜 실패이므로, 표에는 늘 기준 시각이 붙는다.
8. **클라이언트도 조용히 실패하지 않는다.** XHR 이 401/403 이면 폴링을 멈추고 버튼을
   되살리고 이유를 낸다. 그래서 REST 에는 웹수도를 걸지 않는다(아래).
9. **판정 규칙은 `Judge` 한 곳에만 있다.** 화면·CSV·설명서가 같은 열거형과 같은 판정을
    보아야 설명서와 실제 동작이 어긋나지 않는다.
10. **참조 스캐너는 매크로를 열거하지 않는다.** `ri:attachment` 엘리먼트를 트리 어디서든
    전부 걷는다(실측 V5). 예외는 평문 파라미터를 쓰는 `gallery` 하나뿐이다.
    매크로 목록을 다시 만들지 말 것 — 목록은 빠뜨리는 쪽으로 틀린다.
11. **본문 형식이 둘이다.** 페이지·블로그·댓글은 XHTML, **스페이스 설명은 wiki**
    (실측 10번). 하나의 파서에 넣으면 스페이스 설명이 전부 분석 실패로 잡힌다.
12. **배너와 표는 서로 다른 실행을 본다.** 배너는 `latestRun()`(상태 불문), 표는
    `latestCompleteRun()` 이다. 스캔이 실패했다고 마지막으로 성공한 표를 치워 버리면
    관리자는 아무것도 못 보고, 그렇다고 날짜 없이 옛 표만 두면 방금 것으로 읽는다.
    둘을 함께 내고 다르면 `aj.state.stale` 로 다르다고 적는다. 취소된 실행도 저장한다 —
    흔적이 없으면 취소가 없었던 것처럼 보인다.

## 함정 (직접 밟고 고친 것들)

전부 [docs/00-환경실측.md](docs/00-환경실측.md) 에 근거와 함께 있다. 요약만:

- **백그라운드 스레드의 AO 쓰기도 SAL `TransactionTemplate` 으로 감싸야 한다.**
  Confluence 의 AO 는 호스트 Hibernate 세션 위에서 돈다. 감싸지 않으면
  `No Hibernate Session bound to thread` 다. 읽기와 쓰기는 별도 트랜잭션으로 나눈다 —
  하나로 묶으면 대형 인스턴스에서 스캔 내내 트랜잭션이 열려 있다(실측 1번).
- **SAL `@WebSudoRequired` 는 Confluence 플러그인 서블릿에 아무 효과가 없다.**
  애노테이션만 붙인 상태에서 로그인만 한 세션으로 화면이 그대로 열렸다.
  `WebSudoManager` 를 서블릿에서 직접 부른다(실측 3번).
  **REST 에는 걸지 않는다** — 스캔이 웹수도 제한 시간보다 오래 걸리면 폴링이 도중에
  끊기고, 관리자는 "눌렀는데 버튼이 죽었다"만 본다.
- **`catch (RuntimeException)` 으로는 부족하다.** 7.8.1 로 컴파일해 7.12.3 에서 돌리므로
  현실적인 실패는 `NoSuchMethodError` / `NoClassDefFoundError` — 전부 `Error` 다.
  스캔 경로는 `Throwable` 을 잡는다. 안 잡으면 진행률이 RUNNING 에 박혀 화면이 영구
  폴링한다.
- **i18n `.properties` 는 ISO-8859-1 로 읽힌다.** 한글은 `\uXXXX` 로 escape 해야 한다.
  `i18n/*.properties.src`(UTF-8)를 고치고 `tools/make-i18n.py` 로 생성한다.
  `src/main/resources/attachment-janitor*.properties` 를 직접 고치지 말 것.
  `build.sh` / `deploy.sh` 가 빌드 전에 자동으로 다시 만든다.
- **plugin-icon/logo 는 PNG 다.** SVG 는 UPM 의 ImageIO 가 디코드하지 못해 빈칸이 된다
  (easy-numbering 에서 겪은 것). `images/plugin.png` 가 원본이고 `tools/make-icons.py` 가
  72·144 두 크기를 만든다. **원본을 바꿨으면 이걸 다시 돌려야 한다**(실측 22번).
- **관리 화면 제목을 본문에 찍지 않는다.** `atl.admin` 장식이 `<title>` 을 제목으로 이미
  그린다. `<h1>` 을 더하면 화면 맨 위에 제목이 두 번 나온다(실측 21번).
- **`.mvn/jvm.config` 를 지우지 말 것.** `-Duser.name=BSKIM` 한 줄이 없으면 jar 매니페스트의
  `Built-By` 에 빌드한 사람의 OS 계정명이 실린다.
- **같은 버전을 다시 설치하면 브라우저가 이전 빌드의 JS 를 쓴다.** Confluence 가 웹 리소스
  URL 을 플러그인 버전으로 키잉하고 immutable 로 준다. `deploy.sh` 가 버전에 빌드 시각을
  붙이는 이유다. 이 앱은 JS 를 서블릿이 인라인으로 내보내므로 영향이 작지만, web-resource
  를 추가하는 순간 다시 문제가 된다.
- **본문에 HTML 엔티티가 그대로 있다.** DTD 없는 XML 이라 파서가 첫 `&nbsp;` 에서 죽는다.
  선언이 아니라 치환으로 없앤다(`HtmlEntities`, 실측 11번). 파서는 **네임스페이스
  비인식**으로 돌린다 — `ac:`/`ri:` 프리픽스가 선언 없이 들어 있기 때문이다.
- **`PageManager` 가 `ContentEntityManager` 를 상속한다.** 둘 다 `@ComponentImport` 하면
  `NoUniqueBeanDefinitionException` 으로 앱이 기동하지 않는다(실측 12번).
- **AO 엔티티는 `atlassian-plugin.xml` 의 `<ao>` 에도 등록해야 한다.** 빠뜨리면 컴파일도
  기동도 되고 **스캔 저장 단계에서만** 테이블 없음으로 죽는다(실측 13번).
- **`{0}` 이 있는 문구에 숫자를 넘기면 자릿수 구분이 들어간다**(1095 → `1,095`).
  `MessageFormat` 의 정상 동작이다.
- **스캔 스레드에는 보는 사람의 로케일이 없다.** 그래서 스캔은 사람이 읽는 문장을 저장하지
  않는다. 화면 문구는 전부 서블릿이 요청 시점에 i18n 으로 만들어 JSON 으로 넘긴다.

## 검증 방법

```bash
# 위 세 환경변수가 export 되어 있다고 본다.
python3 tools/make-testdata.py                 # 픽스처 (라벨/배지별로 하나씩)
python3 tools/make-testdata.py --bulk 2000     # 성능 측정용 첨부 대량 생성

curl -s -u "$CONFLUENCE_USER:$CONFLUENCE_PASS" -H 'X-Atlassian-Token: no-check' \
     -X POST "$CONFLUENCE_BASE/rest/attachment-janitor/1.0/report/scan"
curl -s -u "$CONFLUENCE_USER:$CONFLUENCE_PASS" \
     "$CONFLUENCE_BASE/rest/attachment-janitor/1.0/report/spaces" | python3 -m json.tool

# 교차 확인 — 앱 수치와 DB 수치가 같아야 한다 (쿼리는 docs/00-환경실측.md V4)
docker exec <db-container> psql -U <user> -d confluence -c "..."

# 권한: 비관리자는 전부 403, 익명은 401
curl -s -u "<비관리자 계정>:..." -o /dev/null -w '%{http_code}\n' \
     "$CONFLUENCE_BASE/rest/attachment-janitor/1.0/report/spaces"

# 중복 실행 방지: 하나만 202, 나머지는 409
for i in 1 2 3; do (curl -s -o /dev/null -w "$i=%{http_code} " \
  -u "$CONFLUENCE_USER:$CONFLUENCE_PASS" -H 'X-Atlassian-Token: no-check' \
  -X POST "$CONFLUENCE_BASE/rest/attachment-janitor/1.0/report/scan") & done; wait
```

권한 시험에는 관리자 권한이 **없는** 계정 하나가 따로 필요하다. 계정명·비밀번호는
저장소에 적지 않는다.

## 남은 일

- **변경 동작(삭제 · 구버전 정리 · 이동)** — 사용자와 별도로 논의하기로 한 주제다.
  넣는다면 확인 절차 · 실행 로그 · 드라이런이 함께 가야 한다.
- 삭제된 첨부의 용량을 정직하게 세는 방법 (실측 2번).
- 본문 단계의 확장성 측정 (실측 17번). `getPages(space, true)` 가 스페이스의 페이지를
  전부 메모리에 올린다. 이 인스턴스는 페이지가 39개뿐이라 측정하지 못했다.
- 페이지 템플릿·블루프린트 본문 스캔 (`pagetemplates` 는 별도 테이블이다, 실측 V6).
- 스페이스 로고(SPACEDESCRIPTION 컨테이너) 실측 — 이 인스턴스에 로고가 없어 못 봤다.
- 7.12.3 에서 **돌려** 보기 (`atlas-run`). 컴파일은 통과했다(실측 19번) — 통과한 것은
  "API 가 그대로 있다"까지고 런타임 동작은 아직 안 봤다. 형제 앱처럼 대조 문서를 남긴다.
- **DC 클러스터 잠금.** `atlassian-data-center-compatible=true` 를 선언했지만 중복 실행
  방지(`AtomicBoolean`)는 **노드 하나 안에서만** 유효하다. 노드 두 대가 동시에 스캔하면
  둘 다 202 를 받고 ScanRun 이 두 개 저장된다. 조회 전용이라 손상은 없지만(멱등이고
  보관 세대 정리가 치운다) 11장 8단계에서 클러스터 잠금으로 바꾼다.
- **구버전 조회 비용.** 실측 4번. 지금은 첨부당 1회 쿼리(약 14ms)다. 구버전이 많은
  인스턴스가 바로 이 앱의 대상이므로 여기가 사실상의 성능 상한이다.
- **진행률 공백.** 중복·저장 단계는 끝날 때까지 0% 로 보인다 — `DuplicateFinder` 안에
  진행 콜백이 없고 취소도 그 안에서는 확인하지 않는다. 해시 대상이 많은 인스턴스에서
  "멈춘 것처럼" 보일 수 있다.
- **남은 N+1.** 중복 화면은 그룹마다 구성원을 따로 조회한다(`attachmentsWithHash`).
  그룹 수가 많으면 여기도 묶어야 한다. 근거 조회는 이미 묶었다(실측 20번).
- **대형 인스턴스 메모리.** 실측 8번. `findLatestVersionsIterator()` 는 `Query.iterate()`
  라 읽은 엔티티가 트랜잭션이 끝날 때까지 1차 캐시에 남는다. 스캔 전체가 트랜잭션
  하나이므로 컨텍스트가 첨부 수에 비례해 커진다. **2,044개까지만 측정했다.**
  바운드가 필요하면 스페이스 단위로 트랜잭션을 쪼갠다(출발점은 8번).
