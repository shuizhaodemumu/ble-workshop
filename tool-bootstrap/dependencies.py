import pathlib,urllib.request,xml.etree.ElementTree as E,zipfile,concurrent.futures,json,time
root=pathlib.Path('work/android-tools/deps');root.mkdir(parents=True,exist_ok=True)
opener=urllib.request.build_opener(urllib.request.ProxyHandler({'https':'http://127.0.0.1:7890'}))
def get(url,path):
 if path.exists() and path.stat().st_size:return path.read_bytes()
 for attempt in range(3):
  try:
   with opener.open(url,timeout=120) as r:data=r.read()
   path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return data
  except Exception:
   if attempt==2:raise
   time.sleep(1)
# Versions come from the pinned DFU POM and AndroidX POMs. Explicit jars avoid
# dynamic dependency selection; AndroidX annotations are compile-time metadata.
libs=[
 ('no.nordicsemi.android','dfu','2.11.0','aar'),
 ('org.jetbrains.kotlin','kotlin-stdlib','2.3.10','jar'),
 ('com.google.code.gson','gson','2.13.2','jar'),
 ('androidx.core','core','1.12.0','aar'),
 ('androidx.localbroadcastmanager','localbroadcastmanager','1.1.0','aar'),
 ('androidx.annotation','annotation-jvm','1.9.1','jar'),
 ('androidx.annotation','annotation-experimental','1.3.0','aar'),
 ('androidx.collection','collection','1.0.0','jar'),
 ('androidx.concurrent','concurrent-futures','1.1.0','jar'),
 ('com.google.guava','listenablefuture','1.0','jar'),
 ('androidx.lifecycle','lifecycle-runtime','2.6.2','aar'),
 ('androidx.lifecycle','lifecycle-common','2.6.2','jar'),
 ('androidx.arch.core','core-common','2.2.0','jar'),
 ('androidx.arch.core','core-runtime','2.2.0','aar'),
 ('androidx.versionedparcelable','versionedparcelable','1.1.1','aar'),
 ('org.jetbrains','annotations','23.0.0','jar'),
 ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.7.3','jar'),
 ('org.jetbrains.kotlinx','kotlinx-coroutines-android','1.7.3','jar'),
]
def one(lib):
 g,a,v,ext=lib;origin='https://dl.google.com/dl/android/maven2/' if g.startswith('androidx.') else 'https://repo.maven.apache.org/maven2/'
 base=origin+g.replace('.','/')+'/'+a+'/'+v+'/'+a+'-'+v
 path=root/(a+'-'+v+'.'+ext);get(base+'.'+ext,path);get(base+'.pom',root/(a+'-'+v+'.pom'))
 if ext=='aar':
  dest=root/(a+'-'+v);dest.mkdir(exist_ok=True)
  with zipfile.ZipFile(path) as z:
   for n in z.namelist():
    if n.endswith('/'):continue
    if n=='classes.jar' or n=='AndroidManifest.xml' or n=='R.txt' or n.startswith('res/'):
     if '..' in pathlib.PurePosixPath(n).parts:raise ValueError(n)
     out=dest/n;out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes(z.read(n))
 print('OK',a,v,flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:list(ex.map(one,libs))
(root/'versions.json').write_text(json.dumps(libs,indent=2))
