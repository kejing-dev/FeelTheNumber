"""Renders the launcher icon (res/drawable/ic_launcher_foreground.xml on #2D8CE0) to a 512 px PNG.
Same geometry as the vector (108-unit viewport), drawn 4x and downsampled."""
import sys
from PIL import Image, ImageDraw

S = 512 * 4
k = S / 108.0
img = Image.new("RGB", (S, S), (0x2D, 0x8C, 0xE0))
d = ImageDraw.Draw(img)
W = (255, 255, 255)
def c(x, y, r, fill):
    d.ellipse([(x - r) * k, (y - r) * k, (x + r) * k, (y + r) * k], fill=fill)
d.line([(22 * k, 76 * k), (86 * k, 76 * k)], fill=W, width=int(2.5 * k))
for x in (22, 86):
    c(x, 76, 1.25, W)
c(62, 57, 19, W)
# highlight arc: from (51,50) to (61,43.5) on a circle of radius 13 around the ball's upper left
d.arc([(62 - 13) * k, (57 - 13) * k, (62 + 13) * k, (57 + 13) * k], start=212, end=268, fill=(0xA9, 0xD3, 0xF5), width=int(3 * k))
c(33, 60.5, 2.6, W)
d.rounded_rectangle([30 * k, 64.5 * k, 36 * k, 76 * k], radius=3 * k, fill=W)
img.resize((512, 512), Image.LANCZOS).save(sys.argv[1])
print(sys.argv[1])
