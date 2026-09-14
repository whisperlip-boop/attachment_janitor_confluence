#!/usr/bin/env python3
"""images/plugin.png 에서 UPM 용 plugin-icon / plugin-logo PNG 를 만든다.

SVG 를 쓰면 UPM 의 ImageIO 가 디코드하지 못해 아이콘 자리가 빈다. 그래서 PNG 로만 낸다.
원본을 그대로 두 슬롯에 꽂지 않는 이유는 크기다 — 512x512 를 목록에 72px 로 그리면
브라우저가 매번 축소하고 jar 도 그만큼 무거워진다.

외부 이미지 라이브러리는 쓰지 않는다. 빌드 머신에 Pillow 가 있다고 가정하지 않으려고
PNG 를 직접 읽고 쓴다. 원본은 8비트 RGBA(색 타입 6) · 인터레이스 없음이어야 한다.

  python3 tools/make-icons.py
"""
import os
import struct
import sys
import zlib

IMAGES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      "src", "main", "resources", "images")
SOURCE = os.path.join(IMAGES, "plugin.png")

# UPM 이 목록에서 쓰는 크기와 상세에서 쓰는 크기.
TARGETS = [("pluginIcon.png", 72), ("pluginLogo.png", 144)]


def read_png(path):
    """8비트 RGBA PNG 를 (너비, 높이, bytearray) 로 읽는다."""
    with open(path, "rb") as handle:
        data = handle.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit("PNG 가 아니다: " + path)

    width = height = None
    compressed = b""
    offset = 8
    while offset < len(data):
        length, = struct.unpack(">I", data[offset:offset + 4])
        kind = data[offset + 4:offset + 8]
        body = data[offset + 8:offset + 8 + length]
        offset += 12 + length

        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or color != 6 or interlace != 0:
                raise SystemExit(
                    "8비트 RGBA · 인터레이스 없는 PNG 만 읽는다 "
                    "(depth=%d color=%d interlace=%d)" % (depth, color, interlace))
        elif kind == b"IDAT":
            compressed += body
        elif kind == b"IEND":
            break

    raw = zlib.decompress(compressed)
    stride = width * 4
    pixels = bytearray(height * stride)

    # 필터 해제. PNG 는 줄마다 앞 줄·왼쪽 픽셀과의 차분을 기록한다.
    position = 0
    for row in range(height):
        filter_type = raw[position]
        position += 1
        line = raw[position:position + stride]
        position += stride
        out = row * stride
        previous = out - stride
        for index in range(stride):
            value = line[index]
            left = pixels[out + index - 4] if index >= 4 else 0
            up = pixels[previous + index] if row > 0 else 0
            upleft = pixels[previous + index - 4] if row > 0 and index >= 4 else 0
            if filter_type == 1:
                value += left
            elif filter_type == 2:
                value += up
            elif filter_type == 3:
                value += (left + up) // 2
            elif filter_type == 4:
                predictor = left + up - upleft
                distance_left = abs(predictor - left)
                distance_up = abs(predictor - up)
                distance_upleft = abs(predictor - upleft)
                if distance_left <= distance_up and distance_left <= distance_upleft:
                    value += left
                elif distance_up <= distance_upleft:
                    value += up
                else:
                    value += upleft
            elif filter_type != 0:
                raise SystemExit("모르는 필터 %d" % filter_type)
            pixels[out + index] = value & 0xFF
    return width, height, pixels


def resize(width, height, pixels, size):
    """상자 평균으로 줄인다.

    알파를 곱해 두고 평균한 뒤 되돌린다. 그냥 평균하면 투명한 픽셀의 색이 섞여 들어와
    테두리에 어두운 띠가 생긴다.
    """
    out = bytearray(size * size * 4)
    for y in range(size):
        y0 = y * height // size
        y1 = max(y0 + 1, (y + 1) * height // size)
        for x in range(size):
            x0 = x * width // size
            x1 = max(x0 + 1, (x + 1) * width // size)

            red = green = blue = alpha = 0
            count = 0
            for sy in range(y0, y1):
                base = sy * width * 4
                for sx in range(x0, x1):
                    offset = base + sx * 4
                    a = pixels[offset + 3]
                    red += pixels[offset] * a
                    green += pixels[offset + 1] * a
                    blue += pixels[offset + 2] * a
                    alpha += a
                    count += 1

            target = (y * size + x) * 4
            if alpha == 0:
                out[target:target + 4] = b"\x00\x00\x00\x00"
                continue
            out[target] = red // alpha
            out[target + 1] = green // alpha
            out[target + 2] = blue // alpha
            out[target + 3] = alpha // count
    return out


def write_png(path, size, pixels):
    raw = bytearray()
    stride = size * 4
    for row in range(size):
        raw.append(0)  # 필터 없음. 아이콘 크기에서는 차이가 없다.
        raw += pixels[row * stride:(row + 1) * stride]

    def chunk(kind, body):
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    with open(path, "wb") as handle:
        handle.write(b"\x89PNG\r\n\x1a\n")
        handle.write(chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)))
        handle.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        handle.write(chunk(b"IEND", b""))


def main():
    if not os.path.exists(SOURCE):
        raise SystemExit("원본이 없다: " + SOURCE)
    width, height, pixels = read_png(SOURCE)
    if width != height:
        print("경고: 원본이 정사각형이 아니다(%dx%d). 찌그러진다." % (width, height),
              file=sys.stderr)
    for name, size in TARGETS:
        write_png(os.path.join(IMAGES, name), size, resize(width, height, pixels, size))
        print("%s -> %s (%dx%d)" % (os.path.basename(SOURCE), name, size, size))


if __name__ == "__main__":
    main()
