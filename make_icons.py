#!/usr/bin/env python3
# 生成 app 与 runner 的 ic_launcher.png（5 个密度）
import os
from PIL import Image, ImageDraw, ImageFont

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BG_TOP = (18, 26, 32)
BG_BOT = (10, 14, 18)
GREEN = (76, 222, 128)
WHITE = (236, 240, 244)

def find_font(size):
    cands = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf",
    ]
    for c in cands:
        if os.path.exists(c):
            return ImageFont.truetype(c, size)
    return ImageFont.load_default()

def render(px):
    scale = 4
    S = px * scale
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 竖向渐变背景（圆角方形）
    grad = Image.new("RGB", (1, S))
    for y in range(S):
        t = y / max(1, S - 1)
        grad.putpixel((0, y), tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3)))
    grad = grad.resize((S, S))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    # 顶部绿色装饰条
    d.rounded_rectangle([int(S*0.16), int(S*0.16), int(S*0.84), int(S*0.155)+max(2,int(S*0.035))],
                        radius=int(S*0.02), fill=(76, 222, 128, 120))
    # “>_” 提示符
    f = find_font(int(S * 0.42))
    # >
    d.line([(S*0.24, S*0.42), (S*0.40, S*0.53)], fill=GREEN, width=max(2, int(S*0.055)), joint="curve")
    d.line([(S*0.40, S*0.53), (S*0.24, S*0.64)], fill=GREEN, width=max(2, int(S*0.055)), joint="curve")
    d.line([(S*0.46, S*0.66), (S*0.74, S*0.66)], fill=WHITE, width=max(2, int(S*0.055)), joint="curve")
    return img.resize((px, px), Image.LANCZOS)

def out(module):
    for dpi, px in SIZES.items():
        p = f"{module}/src/main/res/mipmap-{dpi}"
        os.makedirs(p, exist_ok=True)
        render(px).save(f"{p}/ic_launcher.png")

out("app")
out("runner")
print("icons ok")
