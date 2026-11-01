"""Draws the PDF Master logo and launcher icons: two stacked white pages with a folded
corner and a red "PDF" badge, on a red gradient.

Run from the project root:  python tool/generate_icons.py
Writes the adaptive-icon layers, legacy icons and the in-app logo into app/src/main/res, and
the store / README logos into branding/.
"""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BRANDING = os.path.join(ROOT, "branding")

TOP = (0xFF, 0x52, 0x52)
BOTTOM = (0xB7, 0x1C, 0x1C)
RED = (0xD3, 0x2F, 0x2F)
INK = (0xE0, 0xE0, 0xE6)
SS = 4  # supersampling factor
BOLD = "C:/Windows/Fonts/segoeuib.ttf"
REGULAR = "C:/Windows/Fonts/segoeui.ttf"

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def font(path, size):
    return ImageFont.truetype(path, size) if os.path.exists(path) else ImageFont.load_default()


def gradient(size):
    """Diagonal light-red (top-left) to deep-red (bottom-right) gradient."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
    return img


def page_shape(x0, y0, w, h, fold):
    """A page outline with its top-right corner folded."""
    return [(x0, y0), (x0 + w - fold, y0), (x0 + w, y0 + fold), (x0 + w, y0 + h), (x0, y0 + h)]


def draw_logo(img, cx, cy, s, mono=False):
    """Draws the two pages centred on (cx, cy); `s` is the front page's height."""
    w, h = s * 0.76, s
    fold = w * 0.28
    r = s * 0.05
    x0, y0 = cx - w / 2 + s * 0.05, cy - h / 2 + s * 0.04
    white = (255, 255, 255, 255)

    # Soft shadow under the pages (colour icons only).
    if not mono:
        shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(shadow)
        sd.rounded_rectangle([x0 - s * 0.08, y0 + s * 0.02, x0 + w + s * 0.02, y0 + h + s * 0.06], radius=r, fill=(80, 0, 0, 110))
        img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(s * 0.04)))

    d = ImageDraw.Draw(img)
    # Back page, offset up and left, tinted so the two sheets read apart.
    bx, by = x0 - s * 0.13, y0 - s * 0.1
    back = (255, 222, 226, 255) if not mono else (255, 255, 255, 110)
    d.rounded_rectangle([bx, by, bx + w, by + h], radius=r, fill=back)

    # The front page casts a soft shadow onto the back page.
    if not mono:
        cast = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(cast).polygon(page_shape(x0 - s * 0.015, y0 + s * 0.01, w, h, fold), fill=(120, 0, 0, 90))
        img.alpha_composite(cast.filter(ImageFilter.GaussianBlur(s * 0.025)))
        d = ImageDraw.Draw(img)

    # Front page with folded corner.
    d.polygon(page_shape(x0, y0, w, h, fold), fill=white)
    d.rounded_rectangle([x0, y0 + fold, x0 + w, y0 + h], radius=r, fill=white)
    d.rectangle([x0, y0 + fold, x0 + w * 0.5, y0 + h - r], fill=white)
    d.polygon([(x0 + w - fold, y0), (x0 + w - fold, y0 + fold), (x0 + w, y0 + fold)],
              fill=(255, 205, 210, 255) if not mono else (0, 0, 0, 0))
    if mono:
        # Keep the fold visible as a cut-out in the single-colour icon.
        d.line([(x0 + w - fold, y0), (x0 + w - fold, y0 + fold), (x0 + w, y0 + fold)], fill=(0, 0, 0, 0), width=max(1, int(s * 0.03)))

    # Text lines.
    line_h = s * 0.045
    lx = x0 + w * 0.14
    for i, frac in enumerate([0.52, 0.72, 0.62]):
        ly = y0 + h * (0.2 + i * 0.12)
        colour = INK + (255,) if not mono else (0, 0, 0, 0)
        d.rounded_rectangle([lx, ly, lx + w * frac, ly + line_h], radius=line_h / 2, fill=colour)

    # "PDF" badge.
    bw, bh = w * 0.86, h * 0.27
    bx0 = x0 + (w - bw) / 2 + w * 0.18
    by0 = y0 + h * 0.6
    if not mono:
        d.rounded_rectangle([bx0, by0, bx0 + bw, by0 + bh], radius=bh * 0.22, fill=RED + (255,))
        f = font(BOLD, int(bh * 0.78))
        tb = d.textbbox((0, 0), "PDF", font=f)
        tx = bx0 + (bw - (tb[2] - tb[0])) / 2 - tb[0]
        ty = by0 + (bh - (tb[3] - tb[1])) / 2 - tb[1]
        d.text((tx, ty), "PDF", font=f, fill=white)
    else:
        f = font(BOLD, int(bh * 0.78))
        tb = d.textbbox((0, 0), "PDF", font=f)
        tx = bx0 + (bw - (tb[2] - tb[0])) / 2 - tb[0]
        ty = by0 + (bh - (tb[3] - tb[1])) / 2 - tb[1]
        d.text((tx, ty), "PDF", font=f, fill=(0, 0, 0, 0))


def render(size, background, scale, mono=False, corner=None):
    big = size * SS
    img = gradient(big).convert("RGBA") if background else Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw_logo(img, big / 2, big / 2, big * scale, mono)
    if corner is not None:
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=big * corner, fill=255)
        img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def save(img, *path):
    full = os.path.join(*path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    img.save(full, optimize=True)


def wordmark(height):
    icon = render(height, True, 0.56, corner=0.22)
    title = font(BOLD, int(height * 0.40))
    sub = font(REGULAR, int(height * 0.15))
    pad = int(height * 0.22)
    t1, t2 = "PDF Master", "ALL-IN-ONE OFFLINE PDF TOOLS"
    tw = max(title.getbbox(t1)[2], sub.getbbox(t2)[2])
    img = Image.new("RGBA", (height + pad + tw + pad // 2, height), (255, 255, 255, 255))
    img.alpha_composite(icon, (0, 0))
    d = ImageDraw.Draw(img)
    x = height + pad
    d.text((x, height * 0.12), "PDF", font=title, fill=RED)
    d.text((x + title.getbbox("PDF ")[2], height * 0.12), "Master", font=title, fill=(0x21, 0x21, 0x21))
    d.text((x, height * 0.66), t2, font=sub, fill=(0x75, 0x75, 0x75))
    return img


def main():
    for name, f in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        fg = round(108 * f)
        # Adaptive layers: the artwork stays inside the 66dp safe zone.
        save(render(fg, False, 0.46), folder, "ic_launcher_foreground.png")
        save(render(fg, False, 0.46, mono=True), folder, "ic_launcher_monochrome.png")
        save(render(round(48 * f), True, 0.58, corner=0.22), folder, "ic_launcher.png")
    save(render(192, True, 0.58, corner=0.22), RES, "drawable-nodpi", "logo.png")
    save(render(1024, True, 0.58), BRANDING, "PDFMaster_logo_1024.png")
    save(render(512, True, 0.58), BRANDING, "PDFMaster_playstore_512.png")
    save(wordmark(256), BRANDING, "PDFMaster_wordmark.png")


if __name__ == "__main__":
    main()
