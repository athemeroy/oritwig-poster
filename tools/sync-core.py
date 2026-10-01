#!/usr/bin/env python3
"""Explicitly update the vendored core to a reviewed immutable upstream commit."""
# SPDX-License-Identifier: GPL-3.0-or-later
import hashlib,json,pathlib,re,shutil,subprocess,sys,tempfile,uuid
root=pathlib.Path(__file__).resolve().parent.parent
if len(sys.argv)!=2 or not re.fullmatch('[0-9a-f]{40}',sys.argv[1]):
    raise SystemExit('Usage: tools/sync-core.py <reviewed-40-character-core-commit>')
commit=sys.argv[1];repo='https://github.com/athemeroy/oritwig-core.git'
apps=[x for x in ('poster','journal') if (root/'apps'/x).is_dir()]
if len(apps)!=1:raise SystemExit('Run this from exactly one standalone Oritwig app checkout')
app=apps[0]
with tempfile.TemporaryDirectory(prefix='oritwig-core-') as temp:
    source=pathlib.Path(temp)/'source'
    subprocess.run(['git','clone','--config','core.autocrlf=false','--filter=blob:none','--no-checkout',repo,str(source)],check=True)
    subprocess.run(['git','-C',str(source),'fetch','--depth=1','origin',commit],check=True)
    got=subprocess.check_output(['git','-C',str(source),'rev-parse','FETCH_HEAD']).decode().strip()
    if got!=commit:raise SystemExit('Fetched a different source commit')
    subprocess.run(['git','-C',str(source),'checkout','--detach',commit],check=True)
    tree=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD:shared/core']).decode().strip()
    target=root/'shared/core';stage=root/'shared'/('core.new-'+str(uuid.uuid4()));backup=root/'shared'/('core.old-'+str(uuid.uuid4()))
    target.parent.mkdir(exist_ok=True)
    shutil.copytree(source/'shared/core',stage)
    entries=[]
    for file in sorted(stage.rglob('*')):
        if file.is_symlink():raise SystemExit('Upstream source contains a symbolic link')
        if file.is_file():entries.append({'path':'shared/core/'+file.relative_to(stage).as_posix(),'sha256':hashlib.sha256(file.read_bytes()).hexdigest()})
    if not entries:raise SystemExit('Empty shared source snapshot')
    notices=[]
    notice_map={'LICENSE':'vendor/core-LICENSE','NOTICE':'vendor/core-NOTICE','README.md':'vendor/core-README.md'}
    for original,destination in notice_map.items():
        if (source/original).is_symlink():raise SystemExit('Upstream notice is a symbolic link')
        output=root/destination;output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source/original,output)
        notices.append({'path':destination,'sha256':hashlib.sha256(output.read_bytes()).hexdigest()})
    for file in sorted((source/'third_party').rglob('*')):
        if file.is_symlink():raise SystemExit('Upstream provenance is a symbolic link')
        if file.is_file():
            relative=file.relative_to(source);output=root/relative;output.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,output)
            notices.append({'path':relative.as_posix(),'sha256':hashlib.sha256(output.read_bytes()).hexdigest()})
    record={'schema':1,'repository':repo,'commit':commit,'source_tree':tree,'consumer':app,'files':entries,'notices':notices}
    data=(json.dumps(record,indent=2,sort_keys=True)+'\n').encode()
    try:
        if target.exists():target.rename(backup)
        stage.rename(target)
        lock=root/'vendor/core.lock.json';temporary=lock.with_suffix('.tmp');temporary.write_bytes(data);temporary.replace(lock)
        asset=root/'apps'/app/'src/main/assets/oritwig/core-source.json';asset.parent.mkdir(parents=True,exist_ok=True);asset.write_bytes(data)
    except BaseException:
        if target.exists():shutil.rmtree(target)
        if backup.exists():backup.rename(target)
        raise
    finally:
        if stage.exists():shutil.rmtree(stage)
    if backup.exists():shutil.rmtree(backup)
subprocess.run([sys.executable,str(root/'tools/verify-core.py')],check=True)
print('Review and commit the updated source, source lock and notices together.')
