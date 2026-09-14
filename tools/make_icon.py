#!/usr/bin/env python3
"""生成儿童桌面 launcher 图标。纯标准库（zlib + struct），不依赖 Pillow。

    python3 tools/make_icon.py preview          # 生成三套候选图，供挑选
    python3 tools/make_icon.py android a        # 把选定方案写入 mipmap-*/ic_launcher.png
"""
import math
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "out")

BLUE_TOP = (92, 156, 255)
BLUE_BOT = (34, 88, 214)
DEEP = (36, 92, 205)
WHITE = (255, 255, 255)
CORAL = (255, 106, 87)
AMBER = (255, 206, 84)
MINT = (94, 226, 178)
ROSE = (255, 150, 176)


# ---------- 形状 ----------

def rounded_rect(x0, y0, x1, y1, r):
    def f(x, y):
        if x < x0 or x > x1 or y < y0 or y > y1:
            return False
        cx = min(max(x, x0 + r), x1 - r)
        cy = min(max(y, y0 + r), y1 - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    return f


def circle(cx, cy, r):
    return lambda x, y: (x - cx) ** 2 + (y - cy) ** 2 <= r * r


def poly(pts):
    def f(x, y):
        inside = False
        n = len(pts)
        for i in range(n):
            x1, y1 = pts[i]
            x2, y2 = pts[(i + 1) % n]
            if (y1 > y) != (y2 > y):
                xin = (x2 - x1) * (y - y1) / (y2 - y1) + x1
                if x < xin:
                    inside = not inside
        return inside
    return f


def arc_band(cx, cy, r, w, a0, a1):
    def f(x, y):
        dx, dy = x - cx, y - cy
        if abs(math.hypot(dx, dy) - r) > w / 2:
            return False
        a = math.atan2(dy, dx)
        while a < a0:
            a += 2 * math.pi
        return a <= a1
    return f


def quad_pts(p0, p1, p2, n=20):
    out = []
    for i in range(n + 1):
        t = i / n
        out.append((
            (1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
            (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1],
        ))
    return out


def fit(pts, x0, y0, x1, y1):
    """把任意点集等比缩放并居中到指定方框里"""
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    s = min((x1 - x0) / w, (y1 - y0) / h)
    ox = (x0 + x1) / 2 - (min(xs) + max(xs)) / 2 * s
    oy = (y0 + y1) / 2 - (min(ys) + max(ys)) / 2 * s
    return [(x * s + ox, y * s + oy) for x, y in pts]


def heart():
    pts = []
    for i in range(96):
        t = 2 * math.pi * i / 96
        pts.append((16 * math.sin(t) ** 3,
                    13 * math.cos(t) - 5 * math.cos(2 * t)
                    - 2 * math.cos(3 * t) - math.cos(4 * t)))
    return fit(pts, 0.34, 0.30, 0.66, 0.66)


def shield():
    pts = [(0.23, 0.26)]
    pts += quad_pts((0.23, 0.26), (0.23, 0.18), (0.32, 0.18))
    pts += [(0.68, 0.18)]
    pts += quad_pts((0.68, 0.18), (0.77, 0.18), (0.77, 0.26))
    pts += [(0.77, 0.44)]
    pts += quad_pts((0.77, 0.44), (0.77, 0.70), (0.50, 0.88))
    pts += quad_pts((0.50, 0.88), (0.23, 0.70), (0.23, 0.44))
    return pts


# ---------- 三套方案 ----------

def design_a():
    """蓝底 + 白房子 + 一张笑脸"""
    return [
        (poly([(0.50, 0.17), (0.14, 0.48), (0.50, 0.48)]), WHITE),
        (poly([(0.50, 0.17), (0.86, 0.48), (0.50, 0.48)]), WHITE),
        (rounded_rect(0.24, 0.43, 0.76, 0.83, 0.07), WHITE),
        (circle(0.415, 0.585, 0.040), DEEP),
        (circle(0.585, 0.585, 0.040), DEEP),
        (arc_band(0.50, 0.595, 0.115, 0.042,
                  math.radians(30), math.radians(150)), DEEP),
    ]


def design_b():
    """蓝底 + 白盾牌 + 珊瑚色心"""
    return [
        (poly(shield()), WHITE),
        (poly(heart()), CORAL),
    ]


def design_c():
    """蓝底 + 四块应用宫格"""
    return [
        (rounded_rect(0.20, 0.20, 0.47, 0.47, 0.075), AMBER),
        (rounded_rect(0.53, 0.20, 0.80, 0.47, 0.075), ROSE),
        (rounded_rect(0.20, 0.53, 0.47, 0.80, 0.075), MINT),
        (rounded_rect(0.53, 0.53, 0.80, 0.80, 0.075), WHITE),
    ]


DESIGNS = {"a": design_a, "b": design_b, "c": design_c}


# ---------- 渲染与写盘 ----------

def render(n, layers, ss=3, mask=None):
    """mask：圆角外只画透明（API 24/25 没有自适应图标，得自带圆角）"""
    inv = 1.0 / (n * ss)
    out = bytearray(n * n * 4)
    for py in range(n):
        for px in range(n):
            r = g = b = 0.0
            hits = 0
            for sy in range(ss):
                for sx in range(ss):
                    x = (px * ss + sx + 0.5) * inv
                    y = (py * ss + sy + 0.5) * inv
                    if mask is not None and not mask(x, y):
                        continue
                    hits += 1
                    t = y
                    col = (BLUE_TOP[0] + (BLUE_BOT[0] - BLUE_TOP[0]) * t,
                           BLUE_TOP[1] + (BLUE_BOT[1] - BLUE_TOP[1]) * t,
                           BLUE_TOP[2] + (BLUE_BOT[2] - BLUE_TOP[2]) * t)
                    for shape, c in layers:
                        if shape(x, y):
                            col = c
                    r += col[0]
                    g += col[1]
                    b += col[2]
            k = ss * ss
            i = (py * n + px) * 4
            if hits:
                out[i] = int(r / hits + 0.5)
                out[i + 1] = int(g / hits + 0.5)
                out[i + 2] = int(b / hits + 0.5)
                out[i + 3] = int(255 * hits / k + 0.5)
    return out


def write_png(path, n, rgba):
    raw = b"".join(b"\x00" + bytes(rgba[y * n * 4:(y + 1) * n * 4]) for y in range(n))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


# 自适应图标前景只占中间 66/108，形状都落在中间 60% 内，不会被动效裁剪切掉
MIPMAPS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "preview"
    if mode == "preview":
        for key, fn in DESIGNS.items():
            p = os.path.join(OUT, f"icon-{key}.png")
            write_png(p, 384, render(384, fn(), ss=2))
            print(p)
        return

    key = (sys.argv[2] if len(sys.argv) > 2 else "a").lower()
    layers = DESIGNS[key]()
    corner = rounded_rect(0.0, 0.0, 1.0, 1.0, 0.21)
    for name, size in MIPMAPS.items():
        p = os.path.join(ROOT, "android", "app", "src", "main", "res",
                         f"mipmap-{name}", "ic_launcher.png")
        write_png(p, size, render(size, layers, ss=4, mask=corner))
        print(p)


if __name__ == "__main__":
    main()
