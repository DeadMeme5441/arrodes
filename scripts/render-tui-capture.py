"""Render an OpenTUI captureSpans JSON frame to PNG for visual review (requires Pillow)."""
import argparse
import math
from pathlib import Path
import json
import unicodedata
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("capture", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--font", default="/System/Library/Fonts/Menlo.ttc")
parser.add_argument("--size", type=int, default=16)
args = parser.parse_args()
frame = json.loads(args.capture.read_text())
try:
    regular = ImageFont.truetype(args.font, args.size)
except OSError:
    regular = ImageFont.truetype("DejaVuSansMono.ttf", args.size)
fonts = {"regular": regular}
for index in range(4):
    try:
        font = ImageFont.truetype(args.font, args.size, index=index)
        style = font.getname()[1].lower()
        if "bold" in style and "italic" not in style:
            fonts["bold"] = font
    except OSError:
        pass
cell = math.ceil(regular.getlength("M"))
line_height = math.ceil(args.size * 1.5)
image = Image.new("RGB", (frame["cols"] * cell, frame["rows"] * line_height))
draw = ImageDraw.Draw(image)
for row, spans in enumerate(frame["lines"]):
    column = 0
    for span in spans:
        width = span["width"]
        left, top = column * cell, row * line_height
        draw.rectangle((left, top, left + width * cell - 1, top + line_height - 1), fill=tuple(span["bg"][:3]))
        attributes = span.get("attributes", 0)
        font = fonts.get("bold", regular) if attributes & 1 else regular
        offset = 0
        ascent, descent = font.getmetrics()
        baseline = top + (line_height - ascent - descent) // 2 + ascent
        for char in span["text"]:
            x = left + offset * cell
            strokes = {"─": "lr", "│": "ud", "┌": "rd", "┐": "ld", "└": "ru", "┘": "lu",
                       "├": "rud", "┤": "lud", "┬": "lrd", "┴": "lru", "┼": "lrud"}
            if char in strokes:
                center = (x + cell // 2, top + line_height // 2)
                ends = {"l": (x, center[1]), "r": (x + cell, center[1]),
                        "u": (center[0], top), "d": (center[0], top + line_height)}
                for direction in strokes[char]:
                    draw.line((center, ends[direction]), fill=tuple(span["fg"][:3]))
            else:
                draw.text((x, baseline), char, font=font, fill=tuple(span["fg"][:3]), anchor="ls")
            if not unicodedata.combining(char):
                offset += 2 if unicodedata.east_asian_width(char) in {"W", "F"} else 1
        if attributes & 8:
            draw.line((left, top + line_height - 3, left + width * cell - 1, top + line_height - 3), fill=tuple(span["fg"][:3]))
        column += width
args.output.parent.mkdir(parents=True, exist_ok=True)
image.save(args.output)
