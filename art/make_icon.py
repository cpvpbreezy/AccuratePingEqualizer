"""Generates the Accurate Ping Equalizer logo (original artwork)."""
from PIL import Image, ImageDraw, ImageFilter
import sys, os

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))

# Background: rounded square with a vertical gradient.
bg = Image.new("RGBA", (S, S))
top, bot = (22, 30, 52), (9, 12, 24)
for y in range(S):
    t = y / (S - 1)
    c = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,)
    ImageDraw.Draw(bg).line([(0, y), (S, y)], fill=c)
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([24, 24, S - 24, S - 24], radius=200, fill=255)
img.paste(bg, (0, 0), mask)

d = ImageDraw.Draw(img)
cx, cy = S // 2, S // 2 + 10
teal = (45, 226, 196, 255)
teal_dim = (45, 226, 196, 90)

# Target rings.
for r, w, col in [(370, 26, teal_dim), (270, 26, teal)]:
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=w)

# Crosshair ticks outside the rings.
for dx, dy in [(0, -1), (0, 1), (-1, 0), (1, 0)]:
    x0, y0 = cx + dx * 300, cy + dy * 300
    x1, y1 = cx + dx * 420, cy + dy * 420
    d.line([(x0, y0), (x1, y1)], fill=teal, width=30)

# Ping bars inside the inner ring, rising left to right; the last bar is the "locked" target.
bars = 4
bw, gap = 62, 30
total_w = bars * bw + (bars - 1) * gap
x = cx - total_w // 2
base_y = cy + 130
heights = [90, 150, 210, 270]
for i, h in enumerate(heights):
    col = (255, 255, 255, 255) if i < bars - 1 else (255, 196, 64, 255)
    d.rounded_rectangle([x, base_y - h, x + bw, base_y], radius=14, fill=col)
    x += bw + gap

# Soft glow under the artwork.
glow = img.filter(ImageFilter.GaussianBlur(18))
out = Image.alpha_composite(glow, img)
out = Image.composite(out, Image.new("RGBA", (S, S), (0, 0, 0, 0)), mask)

dest = sys.argv[1]
out.resize((512, 512), Image.LANCZOS).save(os.path.join(dest, "logo-512.png"))
out.resize((128, 128), Image.LANCZOS).save(os.path.join(dest, "icon.png"))
