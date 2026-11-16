#!/usr/bin/env python3
# Usage: python3 tools/make_campus_map.py app/src/main/res/drawable-nodpi/campus_map.png
# Needs Pillow and the DejaVu fonts. Route points in MapActivity.ROUTES use this picture's pixels.
# Draws a placeholder campus map for the Directions screen.
# Not to scale and not a real survey of the campus; stands in until a real map is added.
import sys
from PIL import Image, ImageDraw, ImageFont
W = H = 1600
img = Image.new("RGB", (W, H), "#EFEBDD")
d = ImageDraw.Draw(img)
B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
R = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
f_lbl = ImageFont.truetype(B, 30)
f_small = ImageFont.truetype(R, 24)
f_road = ImageFont.truetype(B, 26)

lawn, tree, bld, bld_edge, walk, road = "#CFE3D6", "#9CC5AE", "#DED7C4", "#B9B09A", "#FBF8EE", "#FFFFFF"

# lawns
for box in [(60, 60, 700, 480), (900, 680, 1540, 960), (60, 900, 700, 1340), (960, 1180, 1540, 1380)]:
    d.rounded_rectangle(box, 40, fill=lawn)
for x, y in [(140, 140), (240, 380), (600, 120), (980, 760), (1500, 720), (1500, 1250), (1040, 1330), (320, 1180)]:
    d.ellipse((x - 34, y - 34, x + 34, y + 34), fill=tree)

# main road along the bottom
d.rectangle((0, 1500, W, 1600), fill=road)
d.line((0, 1550, W, 1550), fill="#E3DDCB", width=4)
d.text((60, 1515), "MAIN ROAD", font=f_road, fill="#9A927C")

# walkways
for box in [(770, 300, 830, 1500), (100, 790, 1500, 850), (830, 530, 1500, 590), (830, 1050, 1500, 1110)]:
    d.rectangle(box, fill=walk)

def building(box, name, sub=None):
    d.rounded_rectangle(box, 18, fill=bld, outline=bld_edge, width=4)
    cx = (box[0] + box[2]) / 2
    cy = (box[1] + box[3]) / 2
    d.text((cx, cy - (14 if sub else 0)), name, font=f_lbl, fill="#5A5545", anchor="mm")
    if sub:
        d.text((cx, cy + 22), sub, font=f_small, fill="#7A735F", anchor="mm")

building((120, 560, 640, 770), "UC LIBRARY", "Lobby, ground floor")
building((920, 330, 1460, 520), "CITCS BUILDING")
building((960, 880 + 0, 1460, 1040), "CANTEEN", "Food court")
building((300, 300, 700, 470), "ADMIN")
building((120, 1360, 640, 1480), "PARKING")
building((860, 160, 1460, 300), "ENGINEERING")
building((60, 1000, 640, 1280), "GYMNASIUM")

# main gate
d.rectangle((700, 1460, 900, 1500), fill="#07683B")
d.text((800, 1480), "MAIN GATE", font=f_road, fill="white", anchor="mm")
building((860, 1380, 1100, 1450), "SECURITY", None)

img.save(sys.argv[1], optimize=True)
