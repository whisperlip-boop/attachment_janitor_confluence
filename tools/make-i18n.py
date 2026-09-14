#!/usr/bin/env python3
"""i18n 원본(i18n/*.properties.src, UTF-8)을 native2ascii 규칙(\\uXXXX)으로
변환해 src/main/resources/ 에 넣는다.

Confluence의 i18n-resource는 PropertyResourceBundle로 읽히고, Java 8의
PropertyResourceBundle은 .properties를 ISO-8859-1로 해석한다. UTF-8 한글을
그대로 두면 화면에 깨진 글자가 나온다. 그래서 빌드 전에 이 스크립트를 돌린다.

  python3 tools/make-i18n.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "i18n"
DST = ROOT / "src" / "main" / "resources"

HEADER = ("# 이 파일은 생성물이다. 직접 고치지 말고 i18n/{name}.src 를 고친 뒤\n"
          "# tools/make-i18n.py 를 다시 돌릴 것.\n")


def escape(line: str) -> str:
    out = []
    for ch in line:
        if ord(ch) < 128:
            out.append(ch)
        else:
            # BMP 밖(서로게이트 쌍)도 \\uXXXX 두 개로 나온다.
            encoded = ch.encode("utf-16-be")
            for i in range(0, len(encoded), 2):
                out.append("\\u%04x" % int.from_bytes(encoded[i:i + 2], "big"))
    return "".join(out)


def main() -> int:
    sources = sorted(SRC.glob("*.properties.src"))
    if not sources:
        print("i18n/*.properties.src 가 없다", file=sys.stderr)
        return 1
    for source in sources:
        name = source.name[:-len(".src")]
        target = DST / name
        text = source.read_text(encoding="utf-8")
        body = "".join(escape(line) for line in text.splitlines(keepends=True))
        target.write_text(escape(HEADER.format(name=name)) + body, encoding="ascii")
        print("%s -> %s" % (source.relative_to(ROOT), target.relative_to(ROOT)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
