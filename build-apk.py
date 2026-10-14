"""Reproducible local Java / Rust Android packaging, no Gradle daemon required."""
import pathlib,subprocess,os,zipfile,xml.etree.ElementTree as E,shutil,json,sys
root=pathlib.Path(__file__).resolve().parent;workspace=root.parent.parent;tools=workspace/'work/android-tools';build=root/'build';build.mkdir(exist_ok=True)
signing_password=os.environ.get('BLE_KEYSTORE_PASSWORD')
if not signing_password:raise RuntimeError('Set BLE_KEYSTORE_PASSWORD to your private signing password before packaging')
jdk=tools/'jdk/jdk-17.0.20.1+1';bt=tools/'build/android-15';platform=tools/'platform/android-35/android.jar';deps=tools/'deps'
def run(args):
 print('RUN',pathlib.Path(str(args[0])).name,'[signing arguments hidden]' if pathlib.Path(str(args[0])).stem=='keytool' else ' '.join(str(a) for a in args[1:4]),flush=True)
 subprocess.run([str(a) for a in args],check=True,cwd=root)
java=jdk/'bin/java.exe';aapt=bt/'aapt2.exe';resdirs=[p for p in deps.glob('*/res') if any(p.rglob('*.xml'))]
packages=[];jars=[]
for manifest in deps.glob('*/AndroidManifest.xml'):
 packages.append(E.parse(manifest).getroot().attrib['package'])
jars=list(deps.glob('*.jar'))+list(deps.glob('*/classes.jar'))
compiled=[]
for i,res in enumerate(resdirs+[root/'app/res']):
 archive=build/f'res-{i}.zip';run([aapt,'compile','--dir',res,'-o',archive]);compiled.append(archive)
generated=build/'generated';generated.mkdir(exist_ok=True)
resources=build/'resources.apk'
manifest=root/'app/AndroidManifest.xml';debug='--debug' in sys.argv
if debug:
 tree=E.parse(manifest);tree.getroot().find('application').set('{http://schemas.android.com/apk/res/android}debuggable','true');manifest=build/'debug-manifest.xml';tree.write(manifest,encoding='utf-8')
args=[aapt,'link','-o',resources,'--manifest',manifest,'-I',platform,'--java',generated,'--auto-add-overlay','--extra-packages',':'.join(packages),'-A',root/'app/assets','--min-sdk-version','26','--target-sdk-version','35']
for archive in compiled:args+=['-R',archive]
run(args)
classes=build/'classes';classes.mkdir(exist_ok=True)
sources=list((root/'app/src').rglob('*.java'))+list(generated.rglob('*.java'))
argfile=build/'javac.args';argfile.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf8')
run([jdk/'bin/javac.exe','-J-Duser.language=en','-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-source','8','-target','8','-classpath',os.pathsep.join(str(p) for p in [platform,*jars]),'-d',classes,'@'+str(argfile)])
ownjar=build/'app-classes.jar'
with zipfile.ZipFile(ownjar,'w',zipfile.ZIP_DEFLATED) as z:
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes).as_posix())
dex=build/'dex';dex.mkdir(exist_ok=True)
run([java,'-Xmx2g','-cp',tools/'r8-8.13.19.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',platform,'--output',dex,ownjar,*jars])
unpacked=build/'unsigned.apk';shutil.copyfile(resources,unpacked)
so=root/'core/target/aarch64-linux-android/release/libble_core.so'
if not so.exists():raise RuntimeError('Build Rust Android core first')
with zipfile.ZipFile(unpacked,'a',zipfile.ZIP_STORED) as z:
 for p in dex.glob('*.dex'):z.write(p,p.name)
 z.write(so,'lib/arm64-v8a/libble_core.so')
aligned=build/'aligned.apk';run([bt/'zipalign.exe','-P','16','-f','4',unpacked,aligned])
key=workspace/'work/android-signing/bleworkshop.p12';key.parent.mkdir(exist_ok=True)
if not key.exists():run([jdk/'bin/keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','bleworkshop','-keyalg','RSA','-keysize','3072','-validity','7300','-storepass',signing_password,'-keypass',signing_password,'-dname','CN=BLE Workshop Local Build, O=Codex Local, C=CN'])
output=build/'test-debug.apk' if debug else workspace/'outputs/蓝牙工坊_中文版_Rust核心_0.1.2_arm64.apk';output.parent.mkdir(exist_ok=True)
run([java,'-jar',bt/'lib/apksigner.jar','sign','--ks',key,'--ks-key-alias','bleworkshop','--ks-pass','env:BLE_KEYSTORE_PASSWORD','--key-pass','env:BLE_KEYSTORE_PASSWORD','--out',output,aligned])
run([java,'-jar',bt/'lib/apksigner.jar','verify','--verbose',output])
run([bt/'zipalign.exe','-c','-P','16','4',output])
print('APK',output,output.stat().st_size,flush=True)
