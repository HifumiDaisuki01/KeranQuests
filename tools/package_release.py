from pathlib import Path
import subprocess,sys,zipfile,hashlib
root=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(root/'tools/package_editor.py')],check=True)
out=root/'out'
for required in ['KeranQuests-2.0.0.jar','KeranQuests-Client-1.20.1-2.0.0.jar']:
    if not (out/required).is_file():raise SystemExit('Missing build artifact: '+required)
with zipfile.ZipFile(out/'KeranQuests-2.0.0-bundle.zip','w',zipfile.ZIP_DEFLATED) as z:
    for name in ['KeranQuests-2.0.0.jar','KeranQuests-Client-1.20.1-2.0.0.jar','KeranQuests-Editor-2.0.0.html']:
        z.write(out/name,name)
    z.write(root/'docs-v2/CONFIGURATION.md','CONFIGURATION.md')
    for path in (root/'examples').glob('*.yml'):z.write(path,'examples/'+path.name)
    for path in (root/'src/main/resources').rglob('*.yml'):z.write(path,'default-config/'+str(path.relative_to(root/'src/main/resources')))
files=sorted(p for p in out.iterdir() if p.is_file() and p.suffix in ['.jar','.zip','.html'])
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in files))
print('Release bundle and SHA256SUMS ready.')
