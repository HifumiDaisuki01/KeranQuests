from pathlib import Path
import zipfile,hashlib
root=Path(__file__).resolve().parents[1]
version='2.0.0'
html=(root/'editor/index.html').read_text()
html=html.replace('<link rel="stylesheet" href="styles.css">','<style>'+(root/'editor/styles.css').read_text()+'</style>')
for name in ['vendor/js-yaml.min.js','editor-core.js','editor.js']:
    html=html.replace(f'<script src="{name}"></script>','<script>'+(root/'editor'/name).read_text().replace('</script','<\\/script')+'</script>')
out=root/'out';out.mkdir(exist_ok=True)
file=out/f'KeranQuests-Editor-{version}.html';file.write_text(html)
with zipfile.ZipFile(out/f'KeranQuests-Editor-{version}.zip','w',zipfile.ZIP_DEFLATED) as z:
    z.write(file,file.name)
    z.write(root/'editor/vendor/LICENSE-js-yaml','LICENSE-js-yaml.txt')
    z.write(root/'docs-v2/CONFIGURATION.md','配置与升级说明.md')
    for path in (root/'examples').glob('*.yml'):z.write(path,'examples/'+path.name)
print(f'Editor packaged: {file.name}')
