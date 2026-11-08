# Regenerate: python3 -I app/src/fork/make_icons.py app/src/fork/res  (needs Pillow + Noto Sans CJK)
"""YomiTube icons: a bold white 読 on orange, in the same flat style as NewPipe's icon (one white
mark on a solid circle) but with its own mark and colour.

Writes launcher icons (adaptive foreground + legacy round icon), the white status-bar icons that
replace NewPipe's, and the splash-screen glyph. The adaptive background colour is
values/yomitube_colors.xml (ic_launcher_background), which must match ORANGE below."""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = "/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc"
ORANGE = (0xF2, 0x6B, 0x1D, 255)
WHITE = (255, 255, 255, 255)
CH = "読"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def font(size):
    f = ImageFont.truetype(FONT, size, index=0)
    try:
        f.set_variation_by_name("Black")
    except Exception:
        try:
            f.set_variation_by_name("Bold")
        except Exception:
            pass
    return f


def glyph(size, height_fraction):
    """White 読 centred on a transparent square, its ink about height_fraction of the side."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f = font(int(size * height_fraction * 1.15))
    l, t, r, b = d.textbbox((0, 0), CH, font=f)
    d.text(((size - (r - l)) / 2 - l, (size - (b - t)) / 2 - t), CH, font=f, fill=WHITE)
    return im


def round_icon(size):
    """Legacy launcher icon: orange circle with a soft shadow, white 読 (like NewPipe's layout)."""
    big = size * 4
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    m = int(big * 0.04)
    ImageDraw.Draw(shadow).ellipse([m, m + big * 0.015, big - m, big - m + big * 0.015],
                                   fill=(0, 0, 0, 70))
    im.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(big * 0.012)))
    ImageDraw.Draw(im).ellipse([m, m, big - m, big - m], fill=ORANGE)
    im.alpha_composite(glyph(big, 0.50))
    return im.resize((size, size), Image.LANCZOS)


def save(im, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path)


def main(out):
    for name, k in DENSITIES.items():
        # Adaptive foreground (108dp); the mark stays inside the 66dp safe zone.
        fs = int(108 * k)
        save(glyph(fs * 4, 0.36).resize((fs, fs), Image.LANCZOS),
             f"{out}/mipmap-{name}/ic_launcher_foreground.png")
        save(round_icon(int(48 * k)), f"{out}/mipmap-{name}/ic_launcher.png")
        # Status-bar / notification icons: flat white silhouettes (24dp).
        n = int(24 * k)
        g = glyph(n * 8, 0.80).resize((n, n), Image.LANCZOS)
        save(g, f"{out}/drawable-{name}/ic_newpipe_triangle_white.png")
        save(g, f"{out}/drawable-{name}/ic_newpipe_update.png")
    # Splash glyph (shown white on the app's coloured splash background).
    save(glyph(400, 0.80), f"{out}/drawable-nodpi/yomitube_splash_glyph.png")


if __name__ == "__main__":
    main(sys.argv[1])
