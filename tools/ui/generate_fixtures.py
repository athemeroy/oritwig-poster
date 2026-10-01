#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate synthetic raster QA inputs, including EXIF and importer-limit cases."""
from PIL import Image,ImageDraw
from pathlib import Path
import argparse
ap=argparse.ArgumentParser();ap.add_argument('directory');a=ap.parse_args();p=Path(a.directory);p.mkdir(parents=True,exist_ok=True)
im=Image.new('RGB',(640,480));px=im.load()
for y in range(480):
 for x in range(640):px[x,y]=((x*255)//639,(y*255)//479,((x+y)*255)//1118)
d=ImageDraw.Draw(im)
for xy,c in [((0,0,79,79),(255,0,0)),((560,0,639,79),(0,255,0)),((0,400,79,479),(0,0,255)),((560,400,639,479),(255,255,0))]:d.rectangle(xy,fill=c)
d.text((200,210),'RUNTIME SYNTHETIC 640x480',fill=(255,255,255));im.save(p/'synthetic.png')
for orientation in [1,5,7]:
 ex=Image.Exif();ex[274]=orientation;ex[270]='SYNTHETIC PRIVATE METADATA MUST BE STRIPPED';ex[315]='Synthetic QA';im.save(p/f'exif-{orientation}.jpg',quality=95,exif=ex)
Image.new('RGB',(8192,5120),(10,20,30)).save(p/'oversized-40mp.png')
with open(p/'oversized-25mb.jpg','wb') as f:f.write((p/'exif-1.jpg').read_bytes());f.write(bytes(26*1024*1024))
(p/'malformed.png').write_bytes(b'not a valid PNG')
