#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independently inspect exported raster/backup evidence; never modifies inputs."""
import argparse,json,hashlib,zipfile
from pathlib import Path
from PIL import Image,ImageOps,ImageStat
ap=argparse.ArgumentParser();ap.add_argument('paths',nargs='+');a=ap.parse_args()
for name in a.paths:
 p=Path(name);b=p.read_bytes();row={'path':str(p),'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest()}
 if zipfile.is_zipfile(p):
  with zipfile.ZipFile(p) as z:
   row['zip_crc_failure']=z.testzip();row['entries']=[{'name':i.filename,'bytes':i.file_size} for i in z.infolist()]
 else:
  with Image.open(p) as im:
   im.load();rgb=im.convert('RGB');w,h=im.size
   row.update(format=im.format,width=w,height=h,mode=im.mode,info_keys=list(im.info),exif={str(k):str(v) for k,v in im.getexif().items()},corners=[rgb.getpixel((x,y)) for x,y in [(20,20),(w-21,20),(20,h-21),(w-21,h-21)]],extrema=rgb.getextrema(),mean=ImageStat.Stat(rgb).mean)
 print(json.dumps(row))
