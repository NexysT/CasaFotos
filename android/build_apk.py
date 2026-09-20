"""Compilação sem Gradle: Python 3, JDK 17 e Android SDK 35."""
import argparse
from pathlib import Path
import os
import shutil
import subprocess
import zipfile

p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True, help='Pasta Android SDK com platform 35 e build-tools 35.0.0')
p.add_argument('--jdk', required=True, help='Pasta JDK 17')
p.add_argument('--keystore', required=True, help='Ficheiro de assinatura privado, criado por ti')
p.add_argument('--key-alias', default='casafotos', help='Alias da chave de assinatura')
args = p.parse_args()
root = Path(__file__).resolve().parent
sdk, jdk = Path(args.sdk).resolve(), Path(args.jdk).resolve()
bt = sdk / 'build-tools' / '35.0.0'
work = root / 'build-manual'
work.mkdir(exist_ok=True)
env = dict(os.environ, JAVA_HOME=str(jdk))
env['PATH'] = str(jdk / 'bin') + os.pathsep + env.get('PATH', '')
windows = os.name == 'nt'
def tool(name, java=False):
    ext = '.exe' if java or name in ('aapt2', 'zipalign') else '.bat'
    return str((jdk / 'bin' if java else bt) / (name + (ext if windows else '')))
def run(*cmd):
    subprocess.run([str(c) for c in cmd], check=True, env=env, cwd=root)
for folder in ('generated', 'classes', 'dex'):
    dest = work / folder
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir()
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
manifest = manifest.replace('<manifest ', '<manifest package="pt.casafotos.app" ', 1)
(work / 'AndroidManifest.xml').write_text(manifest, encoding='utf-8')
jar = sdk / 'platforms/android-35/android.jar'
run(tool('aapt2'), 'compile', '--dir', root / 'app/src/main/res', '-o', work / 'resources.zip')
run(tool('aapt2'), 'link', '-o', work / 'unsigned.apk', '-I', jar, '--manifest', work / 'AndroidManifest.xml',
    '--java', work / 'generated', '--min-sdk-version', '30', '--target-sdk-version', '35',
    '--version-code', '1', '--version-name', '1.0', work / 'resources.zip')
sources = list((root / 'app/src/main/java').rglob('*.java')) + list((work / 'generated').rglob('*.java'))
run(tool('javac', True), '-encoding', 'UTF-8', '-source', '17', '-target', '17', '-classpath', jar,
    '-d', work / 'classes', *sources)
run(tool('d8'), '--lib', jar, '--min-api', '30', '--output', work / 'dex', *list((work / 'classes').rglob('*.class')))
with zipfile.ZipFile(work / 'unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as apk:
    for dex in (work / 'dex').glob('*.dex'):
        apk.write(dex, dex.name)
run(tool('zipalign'), '-f', '-p', '4', work / 'unsigned.apk', work / 'aligned.apk')
if not os.environ.get('CASAFOTOS_KS_PASS'):
    raise SystemExit('Define CASAFOTOS_KS_PASS apenas na tua sessão antes de assinar o APK.')
run(tool('apksigner'), 'sign', '--ks', args.keystore, '--ks-key-alias', args.key_alias,
    '--ks-pass', 'env:CASAFOTOS_KS_PASS',
    '--out', root.parent / 'CasaFotos.apk', work / 'aligned.apk')
run(tool('apksigner'), 'verify', '--verbose', root.parent / 'CasaFotos.apk')
print('APK criado:', root.parent / 'CasaFotos.apk')
