#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Evidence-first UI driver for a disposable Android emulator.

Runs reviewed JSON plans against visible UI hierarchy nodes. It never calls app
internals to simulate a UI success. Every tap selector is resolved from a fresh
uiautomator dump and its matched node/bounds are recorded. Failures stop the plan.
"""
import argparse,json,os,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
ap=argparse.ArgumentParser();ap.add_argument('plan');ap.add_argument('--serial',default='emulator-5554');ap.add_argument('--out',required=True);ap.add_argument('--expected-avd',default='app-matrix-ci');args=ap.parse_args()
out=Path(args.out);out.mkdir(parents=True,exist_ok=True)
adb=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
if not args.serial.startswith('emulator-'): raise SystemExit('This acceptance plan is restricted to disposable emulators.')
def call(*argv,timeout=60):
 p=subprocess.run([str(adb),'-s',args.serial,*argv],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
 if p.returncode: raise RuntimeError(f'adb {argv}: {p.returncode}: {p.stderr.decode(errors="replace")} {p.stdout[:500]!r}')
 return p.stdout
if call('shell','-n','getprop','ro.kernel.qemu').strip()!=b'1': raise SystemExit('Not an emulator.')
if not args.expected_avd.startswith('app-matrix-'): raise SystemExit('Use a dedicated App Matrix test AVD.')
if call('shell','-n','getprop','ro.boot.qemu.avd_name').decode().strip()!=args.expected_avd: raise SystemExit('Unexpected AVD; refusing to write synthetic test data.')
if call('shell','-n','getprop','sys.boot_completed').strip()!=b'1': raise SystemExit('Android boot is incomplete.')
seq=max([int(p.name.split('-',1)[0]) for p in out.glob('*.png') if p.name.split('-',1)[0].isdigit()] or [0])
def event(kind,**data):
 with (out/'events.jsonl').open('a') as f:f.write(json.dumps({'time':time.time(),'kind':kind,**data})+'\n')
def capture(label):
 global seq
 seq+=1;prefix=out/f'{seq:03}-{re.sub("[^a-zA-Z0-9_-]","-",label)}'
 (prefix.with_suffix('.png')).write_bytes(call('exec-out','screencap','-p'))
 call('shell','-n','uiautomator','dump','/sdcard/app-matrix-window.xml')
 raw=call('shell','-n','cat','/sdcard/app-matrix-window.xml');prefix.with_suffix('.xml').write_bytes(raw)
 return ET.fromstring(raw)
def matches(root,s):
 found=[]
 for n in root.iter('node'):
  if s.get('class') and n.get('class')!=s['class']:continue
  if s.get('id') and n.get('resource-id')!=s['id']:continue
  if 'text' in s and n.get('text','').casefold()!=s['text'].casefold():continue
  if 'contains' in s and s['contains'].casefold() not in n.get('text','').casefold():continue
  if 'desc' in s and n.get('content-desc')!=s['desc']:continue
  if n.get('enabled')=='false' and not s.get('allow_disabled'):continue
  if n.get('visible-to-user')=='false':continue
  found.append(n)
 return found
def tap(selector,scrolls=0):
 for attempt in range(scrolls+1):
  root=capture('before-tap');ns=matches(root,selector)
  if ns:
   clickable=[n for n in ns if n.get('clickable')=='true'];ns=clickable or ns
   if len(ns)!=1:raise AssertionError(f'Ambiguous visible selector {selector}: {[n.attrib for n in ns]}')
   n=ns[0];xy=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   if len(xy)!=4 or xy[0]>=xy[2] or xy[1]>=xy[3]:raise AssertionError(f'Bad bounds: {n.attrib}')
   event('tap',selector=selector,node=n.attrib);call('shell','-n','input','tap',str((xy[0]+xy[2])//2),str((xy[1]+xy[3])//2));time.sleep(.6);return
  if attempt<scrolls:
   panes=[n for n in root.iter('node') if n.get('scrollable')=='true']
   if not panes:break
   x1,y1,x2,y2=map(int,re.findall(r'\d+',panes[-1].get('bounds')))
   call('shell','-n','input','swipe',str((x1+x2)//2),str(y2-50),str((x1+x2)//2),str(y1+50),'400');time.sleep(.6)
 raise AssertionError(f'Missing visible selector {selector}')
def downloads():
 root=capture('picker-navigation')
 # AOSP DocumentsUI locale is English in the specified disposable CI image.
 roots=[n for n in root.iter('node') if n.get('content-desc') in ('Show roots','Open navigation drawer','Show navigation drawer')]
 if roots:tap({'desc':roots[0].get('content-desc')});tap({'text':'Downloads'})
 elif not matches(root,{'text':'Downloads'}):raise AssertionError('Cannot identify Downloads destination from visible picker')
def apply(s):
 kind=s['action'];event('step',step=s)
 if kind in ('start','force_stop') and s['package'] not in ('dev.appmatrix.poster','dev.appmatrix.journal'): raise ValueError('Unexpected test application')
 if kind=='capture':capture(s['label'])
 elif kind=='pickfile':
  root=capture('before-pick-file')
  if not matches(root,{'text':s['name']}):downloads()
  tap({'text':s['name']},s.get('scrolls',4));time.sleep(2);capture('after-pick-file')
 elif kind=='save_document':
  downloads();root=capture('before-document-name');ns=matches(root,{'class':'android.widget.EditText'});assert len(ns)==1,'Ambiguous document filename'
  tap({'class':'android.widget.EditText'});call('shell','-n','input','keyevent','KEYCODE_MOVE_END')
  # Only the visible filename field is edited. No app or provider internals.
  call('shell','-n','input','keyevent',*(['KEYCODE_DEL']*(len(ns[0].get('text',''))+4)))
  assert re.fullmatch(r'[a-zA-Z0-9._-]+',s['name']);call('shell','-n','input','text',s['name']);time.sleep(.4)
  tap({'text':'Save'});time.sleep(2);capture('after-document-save')
 elif kind=='pull':
  assert re.fullmatch(r'[a-zA-Z0-9._-]+',s['name']);call('pull','/sdcard/Download/'+s['name'],str(out/s['name']))
 elif kind=='assert_same_file':
  assert (out/s['first']).read_bytes()==(out/s['second']).read_bytes(),f'Export bytes differ: {s}'
 elif kind=='assert_different_file':
  assert (out/s['first']).read_bytes()!=(out/s['second']).read_bytes(),f'Export bytes unexpectedly identical: {s}'
 elif kind=='slider':
  ns=matches(capture('before-slider'),s['selector']);assert len(ns)==1,f'Expected one visible slider: {s}'
  n=ns[0];assert n.get('class')=='android.widget.SeekBar';x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
  fraction=s['fraction'];assert 0<=fraction<=1
  event('slider',node=n.attrib,fraction=fraction);call('shell','-n','input','tap',str(round(x1+(x2-x1)*fraction)),str((y1+y2)//2));time.sleep(1)
 elif kind=='top':
  root=capture('before-scroll-top');panes=[n for n in root.iter('node') if n.get('scrollable')=='true'];assert panes,'No scrollable pane'
  x1,y1,x2,y2=map(int,re.findall(r'\d+',panes[-1].get('bounds')))
  for i in range(s.get('count',3)):call('shell','-n','input','swipe',str((x1+x2)//2),str(y1+50),str((x1+x2)//2),str(y2-50),'300');time.sleep(.3)
 elif kind=='start':call('shell','-n','am','start','-W','-n',s['package']+'/.MainActivity');time.sleep(2)
 elif kind=='force_stop':call('shell','-n','am','force-stop',s['package'])
 elif kind=='tap':tap(s['selector'],s.get('scrolls',0))
 elif kind=='key':call('shell','-n','input','keyevent',s['key']);time.sleep(.5)
 elif kind=='text':
  text=s['value'];assert re.fullmatch(r'[A-Za-z0-9 ,._:-]+',text),'Use only synthetic safe ASCII text'
  call('shell','-n','input','text',text.replace(' ','%s'));time.sleep(.6)
 elif kind=='assert':
  ns=matches(capture('assert'),s['selector']);assert bool(ns)==s.get('exists',True),f'Assertion failed: {s}'
 elif kind=='wait':time.sleep(min(s['seconds'],15))
 else:raise ValueError(kind)
plan=json.loads(Path(args.plan).read_text())
try:
 for step in plan:apply(step)
 event('result',status='passed',steps=len(plan))
except Exception as e:
 event('result',status='failed',error=str(e))
 try:capture('failure')
 except Exception:pass
 raise
