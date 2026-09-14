#!/usr/bin/env python3
"""기획서 0장(V1~V7) 실측과 라벨 판정 검증을 위한 첨부 픽스처를 만든다.

  CONFLUENCE_BASE=http://<host>:<port> CONFLUENCE_USER=<admin> CONFLUENCE_PASS='...' \
      python3 tools/make-testdata.py

지우고 다시 돌려도 되게 스페이스키/제목으로 기존 것을 재사용한다.
본문 XML 은 직접 적지 않고 wiki 표기를 Confluence 에게 변환시킨다(V5) — 우리가 적은
XML 을 우리가 다시 읽으면 실측이 아니라 자기 입력을 재는 것이 되기 때문이다.

만드는 것 (라벨/배지별로 하나씩)
  AJA  Attachment Janitor Fixture A
    P1 spec           spec.xlsx 를 4번 올려 버전 4개          → 활성 + [구버전 다량]
                      orphan.png                              → 고아
                      한글 이름.png                           → 파일명 인코딩 확인
                      dup-a.bin (dup 바이트)                  → 중복
                      same-size-a.bin (크기 같고 내용 다름)   → 1단계 후보 오탐
    P2 cross          shared.png (본문 참조 없음, B 에서 참조) → 위험
    P3 history        hist.png 를 본문에 넣었다 뺀다           → 이력 참조
    P4 trashed        trash.png 를 붙인 뒤 페이지를 휴지통으로 → 휴지통
    B1 blog           blog.png 를 블로그 본문에서 참조         → 활성(블로그)
    B2 blog           같은 제목의 블로그 두 번째 (제목만으로는 구분 불가)
    P6 entities       &nbsp; · &mdash; 가 든 본문 + 중첩 매크로  → XML 파서 내성
    P7 dangling       존재하지 않는 페이지의 첨부를 참조         → 대상 미확인 참조
    P8 commented      댓글 본문이 첨부를 참조                    → 활성(댓글)
    스페이스 설명      wiki 형식(BodyType 0)으로 첨부 참조        → 두 번째 본문 형식
  AJB  Attachment Janitor Fixture B
    P5 consumer       AJA/cross 의 shared.png 를 교차 참조
                      dup-b.bin (dup 과 같은 바이트, 다른 이름)
                      same-size-b.bin (크기 같고 내용 다름)
                      big.bin (--big-mb, 기본 3MB)            → 대용량
"""
import argparse
import base64
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("CONFLUENCE_BASE")
USER = os.environ.get("CONFLUENCE_USER")
PASS = os.environ.get("CONFLUENCE_PASS")
if not (BASE and USER and PASS):
    sys.exit("CONFLUENCE_BASE / CONFLUENCE_USER / CONFLUENCE_PASS 환경변수가 필요하다")
BASE = BASE.rstrip("/")

AUTH = "Basic " + base64.b64encode(("%s:%s" % (USER, PASS)).encode()).decode()

SPACE_A = "AJA"
SPACE_B = "AJB"


def call(method, path, body=None, headers=None, data=None):
    """JSON 요청. data 가 있으면 그대로 보낸다(멀티파트용)."""
    payload = data
    request = urllib.request.Request(BASE + path, data=payload, method=method)
    request.add_header("Authorization", AUTH)
    request.add_header("Accept", "application/json")
    request.add_header("X-Atlassian-Token", "no-check")
    if body is not None:
        request.add_header("Content-Type", "application/json")
        request.data = json.dumps(body).encode("utf-8")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        raise SystemExit("%s %s -> %s\n%s" % (method, path, error.code, detail[:800]))


def multipart(filename, content, comment=None, minor=False):
    """stdlib 만으로 multipart/form-data 를 만든다(requests 의존을 피한다)."""
    boundary = "----ajfixture" + uuid.uuid4().hex
    parts = []

    def field(name, value):
        parts.append(("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                      % (boundary, name, value)).encode("utf-8"))

    parts.append(("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                  "Content-Type: application/octet-stream\r\n\r\n" % (boundary, filename))
                 .encode("utf-8"))
    parts.append(content)
    parts.append(b"\r\n")
    if comment:
        field("comment", comment)
    field("minorEdit", "true" if minor else "false")
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    return b"".join(parts), "multipart/form-data; boundary=" + boundary


def ensure_space(key, name):
    try:
        return call("GET", "/rest/api/space/" + key)
    except SystemExit:
        pass
    print("스페이스 생성: %s" % key)
    return call("POST", "/rest/api/space", {"key": key, "name": name})


def to_storage(wiki):
    """wiki 표기를 Confluence 가 저장 형식으로 바꾸게 한다 (V5 실측의 전제)."""
    return call("POST", "/rest/api/contentbody/convert/storage",
                {"value": wiki, "representation": "wiki"})["value"]


def find_content(space_key, title, content_type="page"):
    found = call("GET", "/rest/api/content?spaceKey=%s&title=%s&type=%s&expand=version"
                 % (space_key, urllib.request.quote(title), content_type))
    return found["results"][0] if found["results"] else None


def ensure_content(space_key, title, wiki, content_type="page"):
    storage = to_storage(wiki)
    existing = find_content(space_key, title, content_type)
    if existing:
        return call("PUT", "/rest/api/content/" + existing["id"], {
            "id": existing["id"], "type": content_type, "title": title,
            "space": {"key": space_key},
            "body": {"storage": {"value": storage, "representation": "storage"}},
            "version": {"number": existing["version"]["number"] + 1},
        })
    print("  %s 생성: %s" % (content_type, title))
    return call("POST", "/rest/api/content", {
        "type": content_type, "title": title, "space": {"key": space_key},
        "body": {"storage": {"value": storage, "representation": "storage"}},
    })


def upload(content_id, filename, payload, comment=None):
    """같은 이름으로 다시 올리면 Confluence 가 버전을 올린다(V1 실측 대상)."""
    existing = call("GET", "/rest/api/content/%s/child/attachment?filename=%s"
                    % (content_id, urllib.request.quote(filename)))
    body, content_type = multipart(filename, payload, comment)
    if existing["results"]:
        path = "/rest/api/content/%s/child/attachment/%s/data" % (
            content_id, existing["results"][0]["id"])
    else:
        path = "/rest/api/content/%s/child/attachment" % content_id
    return call("POST", path, headers={"Content-Type": content_type}, data=body)


def png(seed, size=512):
    """실제 PNG 헤더로 시작하는 결정적 바이트열. 내용은 중요하지 않고 크기·해시만 쓴다."""
    head = b"\x89PNG\r\n\x1a\n"
    filler = hashlib.sha256(seed.encode()).digest()
    return head + (filler * ((size // len(filler)) + 1))[:size - len(head)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--big-mb", type=int, default=3,
                        help="[대용량] 배지 확인용 파일 크기(MB)")
    parser.add_argument("--bulk", type=int, default=0,
                        help="성능 측정용으로 AJB/bulk 페이지에 추가로 붙일 첨부 수")
    args = parser.parse_args()

    ensure_space(SPACE_A, "Attachment Janitor Fixture A")
    ensure_space(SPACE_B, "Attachment Janitor Fixture B")

    # --- AJA -----------------------------------------------------------------
    # spec.xlsx 는 본문에서 viewfile 매크로로 참조한다. 나머지는 붙이기만 한다.
    spec = ensure_content(SPACE_A, "spec", "{viewfile:name=spec.xlsx}\n\n첨부 4개가 더 붙어 있다.")
    for revision in range(1, 5):
        upload(spec["id"], "spec.xlsx", png("spec-%d" % revision, 4096 * revision),
               comment="revision %d" % revision)
    upload(spec["id"], "orphan.png", png("orphan"))
    upload(spec["id"], "한글 이름.png", png("hangul"))
    upload(spec["id"], "dup-a.bin", png("dup-shared"))
    upload(spec["id"], "same-size-a.bin", png("same-a"))

    # 교차 참조 대상. 자기 본문에는 참조가 없다 → AJB 가 참조하므로 [위험] 이어야 한다.
    cross = ensure_content(SPACE_A, "cross", "이 페이지는 shared.png 를 본문에서 쓰지 않는다.")
    upload(cross["id"], "shared.png", png("shared"))

    # 과거 버전 본문에만 남는 참조 → [이력 참조]
    history = ensure_content(SPACE_A, "history", "!hist.png!")
    upload(history["id"], "hist.png", png("hist"))
    ensure_content(SPACE_A, "history", "이제 본문에서 hist.png 를 뺐다.")

    trashed = ensure_content(SPACE_A, "trashed", "!trash.png!")
    upload(trashed["id"], "trash.png", png("trash"))
    if find_content(SPACE_A, "trashed"):
        call("DELETE", "/rest/api/content/" + trashed["id"])   # 휴지통으로 간다
        print("  trashed 페이지를 휴지통으로 보냈다")

    blog = ensure_content(SPACE_A, "blog", "!blog.png!", content_type="blogpost")
    upload(blog["id"], "blog.png", png("blog"))

    # 같은 제목의 블로그가 둘. (스페이스, 제목) 만으로는 대상을 특정할 수 없다는 것을
    # 스캐너가 알아야 한다 — 페이지는 제목이 유일하지만 블로그는 날짜가 다르면 겹친다.
    try:
        call("POST", "/rest/api/content", {
            "type": "blogpost", "title": "blog", "space": {"key": SPACE_A},
            "body": {"storage": {"value": "<p>same title, different day</p>",
                                 "representation": "storage"}},
        })
        print("  같은 제목 블로그 두 번째 생성")
    except SystemExit as error:
        print("  같은 제목 블로그는 만들지 못했다(제약이 있는 듯): %s" % str(error)[:120])

    # HTML 엔티티와 중첩 매크로. 실제 인스턴스 본문의 18/144 가 &nbsp; 를 갖고 있었다 —
    # DTD 없는 XML 이므로 파서가 그냥 죽는다. 그 경로를 픽스처로 고정해 둔다.
    entities = find_content(SPACE_A, "entities")
    body = ('<p>hard&nbsp;space and an em&mdash;dash and an &amp; ampersand.</p>'
            '<ac:layout><ac:layout-section ac:type="two_equal"><ac:layout-cell>'
            '<ac:structured-macro ac:name="viewfile" ac:schema-version="1">'
            '<ac:parameter ac:name="name">'
            '<ri:attachment ri:filename="nested.png" /></ac:parameter>'
            '</ac:structured-macro>'
            '</ac:layout-cell><ac:layout-cell><p>&nbsp;</p></ac:layout-cell>'
            '</ac:layout-section></ac:layout>')
    payload = {"type": "page", "title": "entities", "space": {"key": SPACE_A},
               "body": {"storage": {"value": body, "representation": "storage"}}}
    if entities:
        payload["id"] = entities["id"]
        payload["version"] = {"number": entities["version"]["number"] + 1}
        entities = call("PUT", "/rest/api/content/" + entities["id"], payload)
    else:
        print("  page 생성: entities")
        entities = call("POST", "/rest/api/content", payload)
    upload(entities["id"], "nested.png", png("nested"))

    # 있지도 않은 페이지의 첨부를 가리키는 참조. 이름만 맞는 첨부를 엉뚱하게 [활성] 으로
    # 찍지 않는지 본다.
    ensure_content(SPACE_A, "dangling", "!NOSUCH:ghost page^orphan.png!")

    # 댓글 본문의 참조. 댓글도 CONTENT 행이고 본문이 있다.
    commented = ensure_content(SPACE_A, "commented", "본문에는 첨부 참조가 없다.")
    upload(commented["id"], "in-comment.png", png("in-comment"))
    existing_comments = call("GET", "/rest/api/content/%s/child/comment" % commented["id"])
    if not existing_comments["results"]:
        call("POST", "/rest/api/content", {
            "type": "comment", "container": {"id": commented["id"], "type": "page"},
            "body": {"storage": {
                "value": to_storage("!in-comment.png!"), "representation": "storage"}},
        })
        print("  댓글 생성: commented")

    # --- AJB -----------------------------------------------------------------
    consumer = ensure_content(
        SPACE_B, "consumer",
        "다른 스페이스의 첨부를 참조한다.\n\n"
        "!AJA:cross^shared.png!\n")
    upload(consumer["id"], "dup-b.bin", png("dup-shared"))          # dup-a 와 같은 바이트
    upload(consumer["id"], "same-size-b.bin", png("same-b"))        # 크기만 같다
    upload(consumer["id"], "big.bin", png("big", args.big_mb * 1024 * 1024))

    # 스페이스 설명은 BodyType 0(WIKI) 이다 — 페이지 본문과 형식이 다르다(실측 10번).
    # REST 로 설명을 넣으면 그 형식으로 저장되는지 확인하는 픽스처다.
    try:
        space_a = call("GET", "/rest/api/space/%s?expand=description.plain" % SPACE_A)
        call("PUT", "/rest/api/space/" + SPACE_A, {
            "key": SPACE_A, "name": space_a["name"],
            "description": {"plain": {
                "value": "스페이스 설명에서 !shared.png! 를 참조한다.",
                "representation": "plain"}},
        })
        print("  스페이스 설명 갱신")
    except SystemExit as error:
        print("  스페이스 설명은 REST 로 못 고쳤다: %s" % str(error)[:120])

    if args.bulk:
        bulk = ensure_content(SPACE_B, "bulk", "성능 측정용 첨부 더미.")
        for index in range(args.bulk):
            upload(bulk["id"], "bulk-%05d.bin" % index, png("bulk-%d" % index))
            if index % 100 == 0:
                print("  bulk %d/%d" % (index, args.bulk))

    print("\n픽스처 완료. 스페이스 %s / %s" % (SPACE_A, SPACE_B))


if __name__ == "__main__":
    main()
