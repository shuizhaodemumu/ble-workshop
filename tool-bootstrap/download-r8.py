import pathlib,sys,hashlib
sys.path.insert(0,str(pathlib.Path(__file__).parent))
from remote_extract import fetch,opener
url='https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.19/r8-8.13.19.jar'
data=fetch(url,0,18289697)
with opener.open(url+'.sha1',timeout=30) as r:expected=r.read().decode().strip()
if hashlib.sha1(data).hexdigest()!=expected:raise RuntimeError('R8 checksum mismatch')
pathlib.Path('work/android-tools/r8-8.13.19.jar').write_bytes(data)
print('R8 8.13.19 downloaded and checksum verified',hashlib.sha256(data).hexdigest())
