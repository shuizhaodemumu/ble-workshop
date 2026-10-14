import urllib.request,struct,zipfile,io,pathlib,json,concurrent.futures,time,zlib,argparse
proxy=urllib.request.ProxyHandler({'https':'http://127.0.0.1:7890','http':'http://127.0.0.1:7890'})
opener=urllib.request.build_opener(proxy)
def fetch(url,start,end):
 if end-start+1>2*1024*1024:
  spans=[(a,min(a+1024*1024-1,end)) for a in range(start,end+1,1024*1024)]
  with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
   return b''.join(ex.map(lambda span:fetch(url,*span),spans))
 for attempt in range(4):
  try:
   req=urllib.request.Request(url,headers={'Range':f'bytes={start}-{end}','Accept-Encoding':'identity'})
   with opener.open(req,timeout=90) as r:
    if r.status!=206:raise RuntimeError(f'range unsupported {r.status}')
    b=r.read()
   if len(b)!=end-start+1:raise RuntimeError('short response')
   return b
  except Exception:
   if attempt==3:raise
   time.sleep(1+attempt)
def extract(url,size,dest,kind):
 tail=fetch(url,size-65536,size-1);at=tail.rfind(b'PK\x05\x06');e=struct.unpack_from('<4s4H2IH',tail,at);cdsize,cdstart=e[5:7];cd=fetch(url,cdstart,cdstart+cdsize-1)
 zz=zipfile.ZipFile(io.BytesIO(cd+struct.pack('<4s4H2IH',b'PK\x05\x06',0,0,e[3],e[4],cdsize,0,0)))
 infos=zz.infolist();pathlib.Path(dest).mkdir(parents=True,exist_ok=True)
 def keep(n):
  if kind=='jdk':return '/bin/' in n or '/conf/' in n or n.endswith(('/lib/modules','/lib/jvm.cfg','/lib/tzdb.dat','/lib/tzmappings','/lib/jrt-fs.jar')) or '/lib/security/' in n
  if kind=='platform':return n.endswith('/android.jar')
  if kind=='build':return n.endswith(('aapt2.exe','aapt.exe','zipalign.exe','apksigner.jar','d8.jar','libwinpthread-1.dll')) or '/lib64/' in n
  if kind=='ndk':return '/sysroot/usr/lib/aarch64-linux-android/26/' in n or ('aarch64' in n and n.endswith(('/libunwind.a','.builtins-aarch64-android.a')))
 tasks=[i for i in infos if not i.is_dir() and keep(i.filename)]
 pathlib.Path(dest,'index.json').write_text(json.dumps([i.filename for i in infos]))
 # ZipFile rebases offsets for a synthetic central directory; restore original offsets from raw records.
 offsets={};p=0
 while p<len(cd):
  fields=struct.unpack_from('<4s6H3I5H2I',cd,p);nl,el,cl=fields[10:13];name=cd[p+46:p+46+nl].decode('utf8');offsets[name]=fields[-1];p+=46+nl+el+cl
 print(kind,'files',len(tasks),'compressed MB',round(sum(i.compress_size for i in tasks)/1e6,1),flush=True)
 def one(i):
  path=pathlib.Path(dest)/i.filename
  if path.exists() and path.stat().st_size==i.file_size:return
  off=offsets[i.filename];header=fetch(url,off,off+29);nl,el=struct.unpack_from('<HH',header,26);start=off+30+nl+el
  data=fetch(url,start,start+i.compress_size-1) if i.compress_size else b''
  data=zlib.decompress(data,-15) if i.compress_type==8 else data
  if len(data)!=i.file_size or zlib.crc32(data)&0xffffffff!=i.CRC:raise RuntimeError('integrity error '+i.filename)
  path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);print('OK',i.filename,flush=True)
 with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:list(ex.map(one,tasks))
if __name__=='__main__':
 a=argparse.ArgumentParser();a.add_argument('kind');args=a.parse_args();base=pathlib.Path('work/android-tools')
 if args.kind=='jdk':
  m=json.loads((base/'jdk-meta.json').read_text(encoding='utf-8-sig'));url,size=m['link'],m['size']
 else:
  names={'ndk':('android-ndk-r27c-windows.zip',781511249),'platform':('platform-35_r02.zip',64273788),'build':('build-tools_r35_windows.zip',59878107)};name,size=names[args.kind];url='https://dl.google.com/android/repository/'+name
 extract(url,size,str(base/args.kind),args.kind)
