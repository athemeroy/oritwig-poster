#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Independently verify outputs from all prepared UI plans. No file mutation."""
import argparse,io,hashlib,json,re,zipfile
from pathlib import Path
from PIL import Image,ImageChops,ImageStat
ap=argparse.ArgumentParser();ap.add_argument('--poster-dir',type=Path);ap.add_argument('--journal-dir',type=Path);ap.add_argument('--fixtures',type=Path,required=True);a=ap.parse_args();p,j,f=a.poster_dir,a.journal_dir,a.fixtures;checks=[]
if not p and not j:ap.error('Provide at least one app output directory')
def check(ok,label):
 if not ok:raise AssertionError(label)
 checks.append(label)
def raster(path,size,fmt):
 im=Image.open(path);im.load();check(im.size==size,f'{path.name} dimensions {size}');check(im.format==fmt,f'{path.name} real {fmt}');check(not im.getexif(),f'{path.name} no EXIF');check('exif' not in im.info,f'{path.name} no EXIF bytes');check(b'SYNTHETIC PRIVATE' not in Path(path).read_bytes(),f'{path.name} no source description');return im.convert('RGB')
if p:
 baseline=raster(p/'poster-baseline.png',(640,480),'PNG');fixture=Image.open(f/'synthetic.png').convert('RGB');check(ImageChops.difference(baseline,fixture).getbbox() is None,'Poster unedited export preserves actual input pixels')
 curved=raster(p/'poster-curved.png',(640,480),'PNG');check(ImageChops.difference(baseline,curved).getbbox() is not None,'Poster curves change decoded pixels')
 for a1,b1 in [('poster-baseline.png','poster-cancel-curves.png'),('poster-curved.png','poster-reopened.png')]:check((p/a1).read_bytes()==(p/b1).read_bytes(),f'{a1} = {b1}')
 for orientation,colors in [(5,[(255,0,0),(0,0,255),(0,255,0),(255,255,0)]),(7,[(255,255,0),(0,255,0),(0,0,255),(255,0,0)])]:
  path=p/f'poster-exif-{orientation}.png'
  check(path.exists(),f'Missing required EXIF output: {path.name}')
  im=raster(path,(480,640),'PNG')
  for xy,expected in zip([(20,20),(459,20),(20,619),(459,619)],colors):check(max(abs(x-y) for x,y in zip(im.getpixel(xy),expected))<7,f'EXIF{orientation} corner {xy} correctly oriented')
 for suffix in ['40mp','25mb','malformed']:
  path=p/f'poster-after-{suffix}.png'
  check(path.exists(),f'Missing rejection output: {path.name}');check(path.read_bytes()==(p/'poster-exif-7.png').read_bytes(),f'Rejected {suffix} preserves previous project pixels')
if j:
 jb=raster(j/'journal-baseline.jpg',(640,480),'JPEG');jc=raster(j/'journal-curved.jpg',(640,480),'JPEG');raster(j/'journal-rotated.jpg',(480,640),'JPEG');check(ImageChops.difference(jb,jc).getbbox() is not None,'Journal curves change decoded pixels');check((j/'journal-baseline.jpg').read_bytes()==(j/'journal-cancel-curves.jpg').read_bytes(),'Journal Cancel leaves image export unchanged')
 with zipfile.ZipFile(j/'journal-roundtrip.fjbackup') as z:
  check(z.testzip() is None,'Backup independent ZIP CRC test');members=z.namelist();check(len(members)==len(set(members)),'Backup has no duplicate members');manifest=z.read('journal.properties').decode('iso-8859-1');check(re.search(r'^format=field-journal$',manifest,re.M) is not None,'Backup format');check(re.search(r'^count=3$',manifest,re.M) is not None,'Backup contains three saved observations')
  for name in members:
   if not name.startswith('media/'):continue
   data=z.read(name);im=Image.open(io.BytesIO(data));im.load();check(im.format=='JPEG' and max(im.size)<=2048 and not im.getexif(),f'{name} normalized JPEG');digest=hashlib.sha256(data).hexdigest();check(f'sha256.{name[6:]}={digest}' in manifest,f'{name} independently verified SHA256')
print(json.dumps({'status':'passed','checks':checks},indent=2))
