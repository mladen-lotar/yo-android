#!/usr/bin/env python3
"""Generate the Google Play store graphics from the design system, reproducibly.

Play requires a 512x512 icon and a 1024x500 feature graphic before an app can be listed, and
neither is an Android resource - they are uploaded to the Console, so they cannot be built by
Gradle. Generating them from the same palette and typeface the app uses keeps them from drifting
into "close enough" territory, and means they can be regenerated rather than hand-edited.

    python3 tools/generate-store-assets.py

Note on the mark: the launcher icon is deliberately a flat #9B59B6 square with no glyph, because
the archived original was exactly that. The *store* icon uses the press-kit lockup - the purple
square carrying a white "Yo" - because a store listing showing an empty coloured tile reads as a
broken upload rather than as a design decision.
"""

from __future__ import annotations

import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

REPO_ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_PATH = REPO_ROOT / "app/src/main/res/font/montserrat_bold.ttf"
OUTPUT_DIR = REPO_ROOT / "store"

# "PETER RIVER ... AMETHYST #9B59B6" - the palette the app ships. Not an approximation.
BRAND = (0x9B, 0x59, 0xB6)
WHITE = (0xFF, 0xFF, 0xFF)


def _fitted_font(draw: ImageDraw.ImageDraw, text: str, target_width: int) -> ImageFont.FreeTypeFont:
    """Largest Montserrat Bold whose rendered width fits `target_width`.

    Binary search rather than a hardcoded point size: the same call then produces a correctly
    proportioned mark at any canvas size, so adding a new asset size needs no new magic numbers.
    """
    low, high = 8, 2000
    best = ImageFont.truetype(str(FONT_PATH), low)
    while low <= high:
        middle = (low + high) // 2
        candidate = ImageFont.truetype(str(FONT_PATH), middle)
        left, _, right, _ = draw.textbbox((0, 0), text, font=candidate)
        if right - left <= target_width:
            best = candidate
            low = middle + 1
        else:
            high = middle - 1
    return best


def _draw_centred(image: Image.Image, text: str, width_fraction: float) -> None:
    draw = ImageDraw.Draw(image)
    font = _fitted_font(draw, text, int(image.width * width_fraction))
    # textbbox, not font.getsize: the cap-height box is what should be optically centred, and
    # Montserrat's line box carries enough leading to push the mark visibly low otherwise.
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    draw.text(
        (
            (image.width - (right - left)) / 2 - left,
            (image.height - (bottom - top)) / 2 - top,
        ),
        text,
        font=font,
        fill=WHITE,
    )


def build_icon(size: int = 512) -> Image.Image:
    # RGBA, not RGB: Play's hi-res icon is specified as a 32-bit PNG and its uploader rejects a
    # 24-bit one outright. The square is opaque either way, so this changes the file's format and
    # not its appearance. The feature graphic below must STAY RGB - that one is specified as
    # 24-bit with no alpha, so "fixing" it to match would be the actual regression.
    icon = Image.new("RGBA", (size, size), BRAND)
    _draw_centred(icon, "Yo", width_fraction=0.62)
    return icon


def build_feature_graphic(size: tuple[int, int] = (1024, 500)) -> Image.Image:
    """The banner at the top of the listing.

    Play crops this differently across surfaces and overlays the app icon and title on some of
    them, so the mark sits in the middle third and nothing is placed near an edge.
    """
    graphic = Image.new("RGB", size, BRAND)
    _draw_centred(graphic, "Yo", width_fraction=0.30)
    draw = ImageDraw.Draw(graphic)
    tagline = "IT'S THAT SIMPLE."
    font = _fitted_font(draw, tagline, int(size[0] * 0.34))
    left, top, right, bottom = draw.textbbox((0, 0), tagline, font=font)
    draw.text(
        ((size[0] - (right - left)) / 2 - left, size[1] * 0.72 - top),
        tagline,
        font=font,
        fill=WHITE,
    )
    return graphic


def main() -> int:
    if not FONT_PATH.exists():
        print(f"missing typeface: {FONT_PATH}", file=sys.stderr)
        return 1
    OUTPUT_DIR.mkdir(exist_ok=True)
    written = [
        (OUTPUT_DIR / "play-icon-512.png", build_icon()),
        (OUTPUT_DIR / "play-feature-graphic-1024x500.png", build_feature_graphic()),
    ]
    for path, image in written:
        image.save(path, "PNG", optimize=True)
        print(f"{path.relative_to(REPO_ROOT)}  {image.width}x{image.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
