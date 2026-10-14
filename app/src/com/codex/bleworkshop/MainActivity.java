package com.codex.bleworkshop;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import no.nordicsemi.android.dfu.*;

public final class MainActivity extends Activity {
 private WebView web;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private BluetoothAdapter adapter;
 private BluetoothLeScanner scanner;
 private boolean scanning=false,ready=false,destroyed=false;
 private final Map<String,Session> sessions=new LinkedHashMap<>();
 private final Map<String,ScanResult> devices=new LinkedHashMap<>();
 private final Set<String> changedDevices=new LinkedHashSet<>();
 private boolean startupPermissionsChecked=false,permissionRequestPending=false;
 private final ArrayDeque<String[]> permissionRequests=new ArrayDeque<>();
 private final Runnable signalRefresh=new Runnable(){@Override public void run(){if(!scanning)return;publishSignals();ui.postDelayed(this,3000);}};
 private final List<String> logs=new ArrayList<>();
 private final List<JSONObject> pendingEvents=new ArrayList<>();
 private final Runnable stopScan=()->stopScanning();
 private BluetoothLeAdvertiser advertiser; private AdvertiseCallback adCallback;
 private BluetoothGattServer server; private BluetoothGattCharacteristic serverChar;
 private byte[] serverValue=new byte[0]; private final Map<String,BluetoothDevice> subscribers=new HashMap<>();
 private String firmwareAddress="",firmwarePath="",firmwareDatPath="",firmwareHash="",firmwareFormat="zip"; private int firmwareType=DfuBaseService.TYPE_APPLICATION;
 private DfuServiceController dfuController; private boolean dfuBusy=false;
 private String exportContent="",exportMime="text/plain",exportFilename="";
 private String importPurpose="";
 private int runToken=0; private final List<String> scriptResults=new ArrayList<>();
 private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
 private JSONObject obj(Object... pairs) {JSONObject o=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(JSONException e){throw new IllegalStateException(e);}return o;}
 private void event(String name,JSONObject payload) {ui.post(()->{if(destroyed)return;JSONObject e=obj("type",name,"data",payload);if(!ready){pendingEvents.add(e);return;}web.evaluateJavascript("window.onNative("+e+")",null);});}
 private void log(String text) {ui.post(()->{String line=new SimpleDateFormat("HH:mm:ss.SSS",Locale.ROOT).format(new Date())+"  "+text;logs.add(line);if(logs.size()>4000)logs.remove(0);event("log",obj("line",line));});}
 private void error(Exception e) {log("错误："+e.getClass().getSimpleName()+" · "+(e.getMessage()==null?"操作失败":e.getMessage()));event("error",obj("message",e.getMessage()==null?"操作失败":e.getMessage()));}
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);getWindow().setStatusBarColor(0xff0c151d);getWindow().setNavigationBarColor(0xff0c151d);
  BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);adapter=manager==null?null:manager.getAdapter();
  if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)WebView.setWebContentsDebuggingEnabled(true);
  web=new WebView(this);web.setBackgroundColor(0xff0c151d);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setDomStorageEnabled(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return true;}@Override public void onPageFinished(WebView view,String url){ready=true;for(JSONObject e:pendingEvents)web.evaluateJavascript("window.onNative("+e+")",null);pendingEvents.clear();event("preferences",obj("value",getPreferences(MODE_PRIVATE).getString("preferences","{}")));status();if(!startupPermissionsChecked){startupPermissionsChecked=true;permissions();}}});
  web.addJavascriptInterface(new Bridge(),"Native");setContentView(web);
  try(InputStream in=getAssets().open("index.html")){web.loadDataWithBaseURL("https://ble-workshop.local/",new String(readLimited(in,2*1024*1024),StandardCharsets.UTF_8),"text/html","UTF-8",null);}catch(Exception e){error(e);}
  DfuServiceListenerHelper.registerProgressListener(this,dfuListener);
  DfuServiceListenerHelper.registerLogListener(this,dfuLogListener);
  ActivityManager activities=(ActivityManager)getSystemService(ACTIVITY_SERVICE);for(ActivityManager.RunningServiceInfo s:activities.getRunningServices(50))if(s.service.getClassName().equals(FirmwareService.class.getName())){dfuBusy=true;event("dfu",obj("state","已有固件升级正在运行","busy",true));}
  registerReceiver(bondReceiver,new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
  log("蓝牙工坊 0.1 · Rust 核心已加载；仅在明确操作后访问蓝牙");
 }
 private byte[] readLimited(InputStream in,int max)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("文件超过允许大小");out.write(b,0,n);}return out.toByteArray();}
 private boolean hasPermissions() {boolean location=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;if(Build.VERSION.SDK_INT>=31)return location&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)==PackageManager.PERMISSION_GRANTED;return location;}
 private void permissions() {
  if(permissionRequestPending)return;permissionRequests.clear();
  if(Build.VERSION.SDK_INT>=31){ArrayList<String> nearby=new ArrayList<>();for(String p:new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)nearby.add(p);if(!nearby.isEmpty())permissionRequests.add(nearby.toArray(new String[0]));}
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)permissionRequests.add(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION});
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permissionRequests.add(new String[]{Manifest.permission.POST_NOTIFICATIONS});
  requestNextPermission();
 }
 private void requestNextPermission(){if(destroyed)return;if(permissionRequests.isEmpty()){permissionRequestPending=false;status();return;}permissionRequestPending=true;requestPermissions(permissionRequests.remove(),1);}
 private void requireBluetooth() {if(adapter==null)throw new IllegalStateException("此设备没有蓝牙适配器");if(!hasPermissions())throw new IllegalStateException("请先授予蓝牙权限");if(!adapter.isEnabled())throw new IllegalStateException("请先开启系统蓝牙");status();}
 private void status() {boolean permitted=hasPermissions();boolean enabled=adapter!=null&&(Build.VERSION.SDK_INT<31||permitted)&&adapter.isEnabled();boolean location=true;if(Build.VERSION.SDK_INT<31){LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);location=lm!=null&&lm.isLocationEnabled();}event("status",obj("bluetooth",enabled,"permissions",permitted,"location",location,"sdk",Build.VERSION.SDK_INT,"hardware",adapter!=null));}
 @Override public void onRequestPermissionsResult(int code,String[] p,int[] results){super.onRequestPermissionsResult(code,p,results);if(code==1){status();log(hasPermissions()?"蓝牙权限已授予":"蓝牙权限未完整授予");requestNextPermission();}}
 @Override protected void onResume(){super.onResume();if(web!=null)status();}
 @Override protected void onStop(){super.onStop();stopScanning();}
 @Override protected void onDestroy(){destroyed=true;runToken++;stopScanning();stopAdvertising();for(Session s:new ArrayList<>(sessions.values()))s.close();sessions.clear();try{unregisterReceiver(bondReceiver);}catch(Exception ignored){}DfuServiceListenerHelper.unregisterProgressListener(this,dfuListener);DfuServiceListenerHelper.unregisterLogListener(this,dfuLogListener);web.removeJavascriptInterface("Native");web.destroy();super.onDestroy();}
 public final class Bridge {
  @JavascriptInterface public String core(String op,String input){try{return Core.call(op,input);}catch(Exception e){return obj("ok",false,"error",e.getMessage()).toString();}}
  @JavascriptInterface public void send(String raw){if(raw.length()>8*1024*1024){event("error",obj("message","输入超过 8 MB"));return;}ui.post(()->{try{act(new JSONObject(raw));}catch(Exception e){error(e);}});}
 }
 private void act(JSONObject a)throws Exception {
  String cmd=a.getString("action");
  switch(cmd){
   case "permissions":permissions();break;
   case "bluetooth":if(Build.VERSION.SDK_INT>=31&&!hasPermissions())permissions();else startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));break;
   case "settings":startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));break;
   case "location":startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));break;
   case "scan":if(scanning)stopScanning();else startScanning(a);break;
   case "connect":connect(a.getString("address"),a.optBoolean("auto",false));break;
   case "disconnect":session(a).close();sessions.remove(a.getString("address"));event("disconnected",obj("address",a.getString("address")));break;
   case "discover":{Session s=session(a);s.enqueue("重新发现服务",()->s.gatt.discoverServices());break;}
   case "bond":requireBluetooth();if(!adapter.getRemoteDevice(a.getString("address")).createBond())throw new IOException("系统未接受配对请求");log("已请求系统配对");break;
   case "read":{Session s=session(a);BluetoothGattCharacteristic c=s.characteristic(a);s.enqueue("读取 "+c.getUuid(),()->s.gatt.readCharacteristic(c));break;}
   case "write":{Session s=session(a);BluetoothGattCharacteristic c=s.characteristic(a);byte[] b=Core.bytes(a.optString("value"),a.optBoolean("utf8"));boolean response=a.optBoolean("response",true);int p=c.getProperties();if(response&&(p&BluetoothGattCharacteristic.PROPERTY_WRITE)==0)throw new IOException("该特征不支持带响应写入");if(!response&&(p&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)==0)throw new IOException("该特征不支持无响应写入");int max=response?512:s.mtu-3;if(b.length>max)throw new IOException("写入数据超过 "+max+" 字节，需增大 MTU 或分包");int wt=response?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;s.enqueue("写入 "+Core.hex(b),()->{if(Build.VERSION.SDK_INT>=33)return s.gatt.writeCharacteristic(c,b,wt)==BluetoothStatusCodes.SUCCESS;c.setValue(b);c.setWriteType(wt);return s.gatt.writeCharacteristic(c);});break;}
   case "notify":{Session s=session(a);BluetoothGattCharacteristic c=s.characteristic(a);BluetoothGattDescriptor d=c.getDescriptor(CCCD);if(d==null)throw new IOException("未找到 CCCD 描述符");boolean enabled=a.optBoolean("enabled");boolean indicate=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0;byte[] b=enabled?(indicate?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE):BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;boolean old=s.notifications.contains(a.getString("key"));s.enqueue(enabled?"订阅通知":"取消订阅",()->{if(!s.gatt.setCharacteristicNotification(c,enabled))return false;s.pendingNotifyKey=a.optString("key");s.pendingNotifyState=enabled;s.pendingNotifyOld=old;if(Build.VERSION.SDK_INT>=33){if(s.gatt.writeDescriptor(d,b)==BluetoothStatusCodes.SUCCESS)return true;}else{d.setValue(b);if(s.gatt.writeDescriptor(d))return true;}s.gatt.setCharacteristicNotification(c,old);s.pendingNotifyKey=null;return false;});break;}
   case "descriptorRead":{Session s=session(a);BluetoothGattDescriptor d=s.descriptor(a);s.enqueue("读取描述符",()->s.gatt.readDescriptor(d));break;}
   case "descriptorWrite":{Session s=session(a);BluetoothGattDescriptor d=s.descriptor(a);if(d.getUuid().equals(CCCD))throw new IOException("请通过通知开关修改 CCCD");byte[] b=Core.bytes(a.optString("value"),false);if(b.length>512)throw new IOException("描述符最多 512 字节");s.enqueue("写入描述符",()->{if(Build.VERSION.SDK_INT>=33)return s.gatt.writeDescriptor(d,b)==BluetoothStatusCodes.SUCCESS;d.setValue(b);return s.gatt.writeDescriptor(d);});break;}
   case "mtu":{Session s=session(a);int n=a.optInt("value",247);if(n<23||n>517)throw new IllegalArgumentException("MTU 范围为 23–517");s.enqueue("请求 MTU "+n,()->s.gatt.requestMtu(n));break;}
   case "rssi":{Session s=session(a);s.enqueue("读取信号强度",()->s.gatt.readRemoteRssi());break;}
   case "priority":{Session s=session(a);if(!s.gatt.requestConnectionPriority(a.optInt("value",0)))throw new IOException("连接优先级请求失败");log("已请求连接优先级，实际参数由设备协商");break;}
   case "advertise":if(adCallback!=null)stopAdvertising();else startAdvertising(a);break;
   case "serverValue":{serverValue=Core.bytes(a.optString("value"),a.optBoolean("utf8"));if(serverValue.length>512)throw new IOException("服务端值最多 512 字节");if(serverChar!=null){serverChar.setValue(serverValue);for(BluetoothDevice d:subscribers.values()){boolean accepted;if(Build.VERSION.SDK_INT>=33)accepted=server.notifyCharacteristicChanged(d,serverChar,false,serverValue)==BluetoothStatusCodes.SUCCESS;else accepted=server.notifyCharacteristicChanged(d,serverChar,false);log("服务端通知 "+d.getAddress()+"："+(accepted?"已提交":"提交失败"));}}break;}
   case "firmware":if(dfuBusy)throw new IllegalStateException("升级中不能替换固件");firmwareAddress=a.getString("address");firmwareFormat=a.optString("format","zip");firmwareType=a.optInt("fileType",DfuBaseService.TYPE_APPLICATION);if(!Arrays.asList(1,2,4).contains(firmwareType))throw new IllegalArgumentException("无效固件类型");importPurpose="firmware";pickDocument();break;
   case "firmwareDat":if(dfuBusy)throw new IllegalStateException("升级中不能替换 Init 文件");importPurpose="firmwareDat";pickDocument();break;
   case "dfu":confirmDfu();break;
   case "dfuPause":controlDfu(DfuBaseService.ACTION_PAUSE);break;
   case "dfuResume":controlDfu(DfuBaseService.ACTION_RESUME);break;
   case "dfuAbort":if(dfuBusy)new AlertDialog.Builder(this).setTitle("停止升级？").setMessage("停止后设备可能仍处于引导模式，需要重新升级。").setNegativeButton("继续升级",null).setPositiveButton("停止",(d,w)->controlDfu(DfuBaseService.ACTION_ABORT)).show();break;
   case "export":exportContent=a.optString("content",String.join("\n",logs));exportFilename=a.optString("filename","蓝牙工坊日志.txt");exportMime=a.optString("mime","text/plain");Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(exportMime).putExtra(Intent.EXTRA_TITLE,exportFilename);startActivityForResult(save,2);break;
   case "import":importPurpose=a.optString("purpose","definitions");pickDocument();break;
   case "script":runScript(a);break;
   case "scriptStop":runToken++;event("script",obj("status","已停止"));break;
   case "clearLogs":logs.clear();break;
   case "preferences":getPreferences(MODE_PRIVATE).edit().putString("preferences",a.getJSONObject("value").toString()).apply();break;
   default:throw new IllegalArgumentException("未知操作："+cmd);
  }
 }
 private void startScanning(JSONObject a) {
  if(dfuBusy)throw new IllegalStateException("升级期间请等待固件传输完成");
  requireBluetooth();if(Build.VERSION.SDK_INT<31){LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);if(lm==null||!lm.isLocationEnabled())throw new IllegalStateException("Android 11 及以下扫描需要开启定位服务");}
  scanner=adapter.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException("蓝牙扫描器不可用");devices.clear();changedDevices.clear();int mode=a.optInt("mode",ScanSettings.SCAN_MODE_LOW_LATENCY);if(mode<0||mode>2)mode=2;ArrayList<ScanFilter> filters=new ArrayList<>();String uuid=a.optString("uuid").trim();if(!uuid.isEmpty())filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Core.uuid(uuid))).build());
  scanner.startScan(filters,new ScanSettings.Builder().setScanMode(mode).setReportDelay(0).build(),scanCallback);scanning=true;ui.removeCallbacks(signalRefresh);ui.postDelayed(signalRefresh,3000);ui.removeCallbacks(stopScan);ui.postDelayed(stopScan,Math.max(5,Math.min(a.optInt("seconds",60),300))*1000L);event("scan",obj("active",true));log("开始 BLE 扫描，每 3 秒刷新最新信号");
 }
 private void stopScanning(){ui.removeCallbacks(stopScan);ui.removeCallbacks(signalRefresh);if(!scanning)return;try{if(scanner!=null)scanner.stopScan(scanCallback);}catch(Exception e){log("停止扫描："+e.getMessage());}publishSignals();scanning=false;event("scan",obj("active",false));}
 private void publishSignals(){JSONArray batch=new JSONArray();for(String address:new ArrayList<>(changedDevices)){try{ScanResult r=devices.get(address);if(r==null)continue;ScanRecord record=r.getScanRecord();String name=record==null?null:record.getDeviceName();if(name==null)name=r.getDevice().getName();byte[] bytes=record==null?new byte[0]:record.getBytes();JSONObject parsed=Core.run("advertisement",Core.hex(bytes));long age=Math.max(0,(SystemClock.elapsedRealtimeNanos()-r.getTimestampNanos())/1000000L);batch.put(obj("address",address,"name",name==null?"未命名设备":name,"rssi",r.getRssi(),"connectable",r.isConnectable(),"bonded",r.getDevice().getBondState()==BluetoothDevice.BOND_BONDED,"advertisement",parsed,"time",System.currentTimeMillis()-age,"phy",r.getPrimaryPhy()));}catch(Exception e){error(e);}}changedDevices.clear();event("deviceBatch",obj("devices",batch,"time",System.currentTimeMillis()));}
 private final ScanCallback scanCallback=new ScanCallback(){
  @Override public void onScanResult(int t,ScanResult r){ui.post(()->{if(scanning){String address=r.getDevice().getAddress();devices.put(address,r);changedDevices.add(address);}});}
  @Override public void onScanFailed(int code){ui.post(()->{scanning=false;ui.removeCallbacks(signalRefresh);ui.removeCallbacks(stopScan);event("scan",obj("active",false));log("扫描失败，Android 错误码："+code);event("error",obj("message","扫描失败（"+code+"），请稍后重试"));});}
 };
 private void connect(String address,boolean auto){requireBluetooth();if(dfuBusy)throw new IllegalStateException("固件升级期间不接受新连接");if(!BluetoothAdapter.checkBluetoothAddress(address))throw new IllegalArgumentException("无效蓝牙地址");if(sessions.containsKey(address))return;stopScanning();Session s=new Session(address);sessions.put(address,s);s.gatt=adapter.getRemoteDevice(address).connectGatt(this,auto,s.callback,BluetoothDevice.TRANSPORT_LE);if(s.gatt==null){sessions.remove(address);throw new IllegalStateException("创建连接失败");}event("connection",obj("address",address,"state","正在连接"));s.connectTimeout=()->{if(!s.connected){s.close();sessions.remove(address);event("disconnected",obj("address",address));log("连接超时："+address);}};ui.postDelayed(s.connectTimeout,auto?90000:25000);}
 private Session session(JSONObject a)throws JSONException {Session s=sessions.get(a.getString("address"));if(s==null||!s.connected)throw new IllegalStateException("设备尚未连接");return s;}
 interface StartOperation {boolean start();}
 private final class Operation {final String label;final StartOperation start;Operation(String l,StartOperation s){label=l;start=s;}}
 private final class Session {
  final String address;BluetoothGatt gatt;boolean connected=false,closed=false;int mtu=23;Runnable connectTimeout;
  final ArrayDeque<Operation> queue=new ArrayDeque<>();Operation active;Runnable opTimeout;int lastStatus=0;
  final Map<String,BluetoothGattCharacteristic> chars=new LinkedHashMap<>();final Map<String,BluetoothGattDescriptor> descriptors=new LinkedHashMap<>();final Set<String> notifications=new HashSet<>();String pendingNotifyKey;boolean pendingNotifyState,pendingNotifyOld;
  Session(String a){address=a;}
  BluetoothGattCharacteristic characteristic(JSONObject a)throws JSONException{BluetoothGattCharacteristic c=chars.get(a.getString("key"));if(c==null)throw new IllegalArgumentException("特征值不存在，请重新发现服务");return c;}
  BluetoothGattDescriptor descriptor(JSONObject a)throws JSONException{BluetoothGattDescriptor d=descriptors.get(a.getString("descriptor"));if(d==null)throw new IllegalArgumentException("描述符不存在");return d;}
  void enqueue(String label,StartOperation start){if(queue.size()>50)throw new IllegalStateException("操作队列已满");queue.add(new Operation(label,start));pump();}
  void pump(){if(active!=null||closed||queue.isEmpty())return;active=queue.remove();try{if(!active.start.start()){lastStatus=-1;log(address+" · "+active.label+"：Android 未接受请求");event("operation",obj("address",address,"label",active.label,"status",-1));active=null;pump();return;}}catch(Exception e){lastStatus=-1;error(e);active=null;pump();return;}log(address+" · "+active.label);opTimeout=()->{lastStatus=-2;log(address+" · GATT 操作超时，关闭连接以避免错配响应");close();sessions.remove(address);event("disconnected",obj("address",address));};ui.postDelayed(opTimeout,15000);}
  void done(int status){lastStatus=status;ui.removeCallbacks(opTimeout);if(active!=null){event("operation",obj("address",address,"label",active.label,"status",status));if(status!=BluetoothGatt.GATT_SUCCESS)log("GATT 失败："+status+" · "+active.label);}active=null;pump();}
  void close(){if(closed)return;closed=true;connected=false;if(connectTimeout!=null)ui.removeCallbacks(connectTimeout);if(opTimeout!=null)ui.removeCallbacks(opTimeout);queue.clear();if(gatt!=null){try{gatt.disconnect();}catch(Exception ignored){}gatt.close();} }
  String key(BluetoothGattCharacteristic c){for(Map.Entry<String,BluetoothGattCharacteristic> e:chars.entrySet())if(e.getValue()==c)return e.getKey();return "";}
  void value(BluetoothGattCharacteristic c,byte[] bytes,String source,int status){event("value",obj("address",address,"key",key(c),"uuid",c.getUuid(),"hex",Core.hex(bytes),"decoded",Core.decode(c.getUuid(),bytes),"source",source,"status",status));log(address+" · "+source+" "+Core.name(c.getUuid())+" = "+Core.hex(bytes));}
  void listServices(){chars.clear();descriptors.clear();JSONArray services=new JSONArray();for(BluetoothGattService service:gatt.getServices()){String sk=service.getUuid()+":"+service.getInstanceId();JSONArray cs=new JSONArray();for(BluetoothGattCharacteristic c:service.getCharacteristics()){String ck=sk+"/"+c.getUuid()+":"+c.getInstanceId();chars.put(ck,c);JSONArray ds=new JSONArray();int n=0;for(BluetoothGattDescriptor d:c.getDescriptors()){String dk=ck+"/"+d.getUuid()+":"+(n++);descriptors.put(dk,d);ds.put(obj("key",dk,"uuid",d.getUuid(),"name",Core.name(d.getUuid()),"permissions",d.getPermissions()));}cs.put(obj("key",ck,"uuid",c.getUuid(),"name",Core.name(c.getUuid()),"properties",c.getProperties(),"descriptors",ds,"notifying",notifications.contains(ck)));}services.put(obj("key",sk,"uuid",service.getUuid(),"name",Core.name(service.getUuid()),"primary",service.getType()==BluetoothGattService.SERVICE_TYPE_PRIMARY,"characteristics",cs));}event("services",obj("address",address,"mtu",mtu,"services",services));}
  final BluetoothGattCallback callback=new BluetoothGattCallback(){
   @Override public void onConnectionStateChange(BluetoothGatt g,int code,int state){ui.post(()->{if(closed)return;if(code==BluetoothGatt.GATT_SUCCESS&&state==BluetoothProfile.STATE_CONNECTED){connected=true;ui.removeCallbacks(connectTimeout);event("connection",obj("address",address,"state","已连接"));log("已连接 "+address);enqueue("发现 GATT 服务",()->g.discoverServices());}else if(state==BluetoothProfile.STATE_DISCONNECTED||code!=BluetoothGatt.GATT_SUCCESS){log("连接断开 "+address+" · 状态 "+code);close();sessions.remove(address);event("disconnected",obj("address",address,"status",code));}});}
   @Override public void onServicesDiscovered(BluetoothGatt g,int status){ui.post(()->{if(closed)return;if(status==0)listServices();done(status);});}
   @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){if(Build.VERSION.SDK_INT<33)read(c,c.getValue(),status);}
   @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] bytes,int status){read(c,bytes,status);}
   void read(BluetoothGattCharacteristic c,byte[] bytes,int status){byte[] copy=bytes==null?new byte[0]:bytes.clone();ui.post(()->{if(closed)return;if(status==0)value(c,copy,"读取",status);done(status);});}
   @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){ui.post(()->{if(!closed)done(status);});}
   @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){if(Build.VERSION.SDK_INT<33)changed(c,c.getValue());}
   @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] bytes){changed(c,bytes);}
   void changed(BluetoothGattCharacteristic c,byte[] bytes){byte[] copy=bytes==null?new byte[0]:bytes.clone();ui.post(()->{if(!closed)value(c,copy,"通知",0);});}
   @Override public void onDescriptorRead(BluetoothGatt g,BluetoothGattDescriptor d,int status){if(Build.VERSION.SDK_INT<33)descriptorValue(d,d.getValue(),status);}
   @Override public void onDescriptorRead(BluetoothGatt g,BluetoothGattDescriptor d,int status,byte[] bytes){descriptorValue(d,bytes,status);}
   void descriptorValue(BluetoothGattDescriptor d,byte[] bytes,int status){byte[] copy=bytes==null?new byte[0]:bytes.clone();ui.post(()->{if(closed)return;event("descriptor",obj("address",address,"uuid",d.getUuid(),"hex",Core.hex(copy),"status",status));done(status);});}
   @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){ui.post(()->{if(closed)return;if(pendingNotifyKey!=null){if(status==0){if(pendingNotifyState)notifications.add(pendingNotifyKey);else notifications.remove(pendingNotifyKey);}else g.setCharacteristicNotification(d.getCharacteristic(),pendingNotifyOld);event("subscription",obj("address",address,"key",pendingNotifyKey,"enabled",notifications.contains(pendingNotifyKey),"status",status));pendingNotifyKey=null;}done(status);});}
   @Override public void onMtuChanged(BluetoothGatt g,int n,int status){ui.post(()->{if(closed)return;if(status==0)mtu=n;event("mtu",obj("address",address,"value",mtu,"status",status));done(status);});}
   @Override public void onReadRemoteRssi(BluetoothGatt g,int rssi,int status){ui.post(()->{if(closed)return;event("rssi",obj("address",address,"value",rssi,"status",status));done(status);});}
  };
 }
 private final BroadcastReceiver bondReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null){int b=i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,0);event("bond",obj("address",d.getAddress(),"state",b));log("配对状态 "+d.getAddress()+"："+(b==12?"已配对":b==11?"正在配对":"未配对"));}}};
 private void startAdvertising(JSONObject a) {
  if(dfuBusy)throw new IllegalStateException("升级期间不能启动广播");
  requireBluetooth();if(!adapter.isMultipleAdvertisementSupported())throw new IllegalStateException("设备不支持 BLE 广播");advertiser=adapter.getBluetoothLeAdvertiser();if(advertiser==null)throw new IllegalStateException("广播器不可用");UUID su=Core.uuid(a.optString("service","FFF0")),cu=Core.uuid(a.optString("characteristic","FFF1"));serverValue=Core.bytes(a.optString("value","00"),false);if(serverValue.length>512)throw new IllegalArgumentException("服务端值最多 512 字节");byte[] manufacturer=Core.bytes(a.optString("manufacturerData",""),false);int id=a.optInt("manufacturerId",89);if(id<0||id>65535)throw new IllegalArgumentException("厂商 ID 范围 0–65535");
  BluetoothManager manager=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE);server=manager.openGattServer(this,serverCallback);if(server==null)throw new IllegalStateException("GATT 服务端不可用");BluetoothGattService service=new BluetoothGattService(su,BluetoothGattService.SERVICE_TYPE_PRIMARY);serverChar=new BluetoothGattCharacteristic(cu,BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE|BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ|BluetoothGattCharacteristic.PERMISSION_WRITE);serverChar.setValue(serverValue);BluetoothGattDescriptor descriptor=new BluetoothGattDescriptor(CCCD,BluetoothGattDescriptor.PERMISSION_READ|BluetoothGattDescriptor.PERMISSION_WRITE);serverChar.addDescriptor(descriptor);service.addCharacteristic(serverChar);if(!server.addService(service)){stopAdvertising();throw new IllegalStateException("无法添加 GATT 服务");}
  AdvertiseData.Builder data=new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(su));if(manufacturer.length>0)data.addManufacturerData(id,manufacturer);AdvertiseSettings settings=new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
  adCallback=new AdvertiseCallback(){@Override public void onStartSuccess(AdvertiseSettings s){event("advertising",obj("active",true,"service",su,"characteristic",cu));log("广播与 GATT 服务端已启动");}@Override public void onStartFailure(int code){ui.post(()->{stopAdvertising();event("error",obj("message","广播失败（"+code+"）。错误 1 通常表示数据超过 31 字节。"));});}};
  try{advertiser.startAdvertising(settings,data.build(),new AdvertiseData.Builder().setIncludeDeviceName(true).build(),adCallback);}catch(Exception e){stopAdvertising();throw e;}
 }
 private void stopAdvertising(){try{if(advertiser!=null&&adCallback!=null)advertiser.stopAdvertising(adCallback);}catch(Exception ignored){}adCallback=null;if(server!=null){server.close();server=null;}serverChar=null;subscribers.clear();event("advertising",obj("active",false));}
 private final BluetoothGattServerCallback serverCallback=new BluetoothGattServerCallback(){
  @Override public void onServiceAdded(int status,BluetoothGattService service){if(status!=0)ui.post(()->{stopAdvertising();event("error",obj("message","GATT 服务添加失败："+status));});}
  @Override public void onConnectionStateChange(BluetoothDevice d,int code,int state){ui.post(()->{if(state==BluetoothProfile.STATE_DISCONNECTED)subscribers.remove(d.getAddress());log("服务端客户端 "+d.getAddress()+"："+(state==2?"已连接":"已断开"));});}
  @Override public void onCharacteristicReadRequest(BluetoothDevice d,int request,int offset,BluetoothGattCharacteristic c){ui.post(()->{if(server==null)return;int code=offset>serverValue.length?BluetoothGatt.GATT_INVALID_OFFSET:BluetoothGatt.GATT_SUCCESS;server.sendResponse(d,request,code,offset,code==0?Arrays.copyOfRange(serverValue,offset,serverValue.length):null);log("服务端读取 "+d.getAddress());});}
  @Override public void onCharacteristicWriteRequest(BluetoothDevice d,int request,BluetoothGattCharacteristic c,boolean prepared,boolean response,int offset,byte[] bytes){byte[] b=bytes.clone();ui.post(()->{if(server==null)return;int status=prepared?BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:offset!=0?BluetoothGatt.GATT_INVALID_OFFSET:b.length>512?BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH:0;if(status==0){serverValue=b;serverChar.setValue(b);event("serverValue",obj("hex",Core.hex(b),"address",d.getAddress()));log("服务端收到写入 "+Core.hex(b));}if(response)server.sendResponse(d,request,status,offset,null);});}
  @Override public void onDescriptorReadRequest(BluetoothDevice d,int request,int offset,BluetoothGattDescriptor descriptor){ui.post(()->{if(server==null)return;byte[] b=subscribers.containsKey(d.getAddress())?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;server.sendResponse(d,request,offset>2?BluetoothGatt.GATT_INVALID_OFFSET:0,offset,offset>2?null:Arrays.copyOfRange(b,offset,2));});}
  @Override public void onDescriptorWriteRequest(BluetoothDevice d,int request,BluetoothGattDescriptor descriptor,boolean prepared,boolean response,int offset,byte[] bytes){byte[] b=bytes.clone();ui.post(()->{if(server==null)return;int status=prepared?BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED:offset!=0?BluetoothGatt.GATT_INVALID_OFFSET:Arrays.equals(b,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)||Arrays.equals(b,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)?0:BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;if(status==0){if(b[0]==1)subscribers.put(d.getAddress(),d);else subscribers.remove(d.getAddress());log("服务端通知订阅数："+subscribers.size());}if(response)server.sendResponse(d,request,status,offset,null);});}
  @Override public void onExecuteWrite(BluetoothDevice d,int request,boolean execute){ui.post(()->{if(server!=null)server.sendResponse(d,request,BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,0,null);});}
 };
 private void pickDocument(){startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),3);}
 @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null)return;Uri uri=intent.getData();if(request==2){try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法打开输出文件");out.write(exportContent.getBytes(StandardCharsets.UTF_8));log("已导出："+exportFilename);}catch(Exception e){error(e);}return;}if(request==3){String purpose=importPurpose;new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法打开选择的文件");byte[] data=readLimited(in,purpose.startsWith("firmware")?32*1024*1024:1024*1024);if(purpose.equals("firmware"))prepareFirmware(data);else if(purpose.equals("firmwareDat")){File f=new File(getCacheDir(),"init.dat");try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}firmwareDatPath=f.getAbsolutePath();event("firmwareDat",obj("bytes",data.length));}else event("imported",obj("purpose",purpose,"content",new String(data,StandardCharsets.UTF_8)));}catch(Exception e){error(e);}},"document-import").start();}}
 private void prepareFirmware(byte[] data)throws Exception {
  if(dfuBusy)throw new IOException("升级进行中，不能替换固件");if(data.length==0)throw new IOException("固件文件为空");int entries=0;long unpacked=0;
  if(firmwareFormat.equals("zip")){boolean manifest=false,binary=false;try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry e;while((e=zip.getNextEntry())!=null){entries++;if(entries>100)throw new IOException("固件 ZIP 文件过多");String name=e.getName();if(name.contains("..")||name.startsWith("/")||name.contains("\\"))throw new IOException("固件包含非法路径");byte[] b=readLimited(zip,32*1024*1024);unpacked+=b.length;if(unpacked>64*1024*1024)throw new IOException("固件解压大小过大");if(name.equals("manifest.json")){new JSONObject(new String(b,StandardCharsets.UTF_8));manifest=true;}if(name.endsWith(".bin")||name.endsWith(".hex"))binary=true;}}if(!binary)throw new IOException("ZIP 中未找到 BIN／HEX 固件");log(manifest?"DFU manifest.json 已验证":"无 manifest.json：将由官方组件识别传统 DFU 格式");}
  else if(!firmwareFormat.equals("hex")&&!firmwareFormat.equals("bin"))throw new IOException("不支持的固件格式");
  File f=new File(getCacheDir(),"firmware."+firmwareFormat);try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(Locale.ROOT,"%02x",b&255));firmwarePath=f.getAbsolutePath();firmwareHash=hash.toString();firmwareDatPath="";event("firmware",obj("bytes",data.length,"sha256",firmwareHash,"entries",entries,"format",firmwareFormat,"address",firmwareAddress));log("固件已载入并计算 SHA-256，尚未写入设备");
 }
 private void confirmDfu() {
  requireBluetooth();if(firmwarePath.isEmpty()||dfuBusy)throw new IllegalStateException("请先选择固件，且等待已有升级结束");if(!BluetoothAdapter.checkBluetoothAddress(firmwareAddress))throw new IllegalArgumentException("无效设备地址");
  new AlertDialog.Builder(this).setTitle("确认写入固件").setMessage("目标："+firmwareAddress+"\n格式："+firmwareFormat+"\nSHA-256："+firmwareHash+"\n\n仅适用于 nRF5 SDK 的 Nordic Legacy／Secure DFU。请确认固件与设备匹配，电量充足。此操作会修改设备固件。").setNegativeButton("取消",null).setPositiveButton("确认升级",(d,w)->{try{stopScanning();for(Session s:new ArrayList<>(sessions.values())){s.close();event("disconnected",obj("address",s.address));}sessions.clear();stopAdvertising();DfuServiceInitiator.createDfuNotificationChannel(this);DfuServiceInitiator starter=new DfuServiceInitiator(firmwareAddress).setDeviceName("蓝牙工坊 DFU").setKeepBond(true).setNumberOfRetries(2).setPacketsReceiptNotificationsEnabled(true).setPacketsReceiptNotificationsValue(12);if(firmwareFormat.equals("zip"))starter.setZip(firmwarePath);else{starter.setBinOrHex(firmwareType,firmwarePath);if(!firmwareDatPath.isEmpty())starter.setInitFile(firmwareDatPath);}dfuController=starter.start(this,FirmwareService.class);dfuBusy=true;event("dfu",obj("state","正在启动升级","busy",true));}catch(Exception e){dfuBusy=false;error(e);}}).show();
 }
 private void controlDfu(int action){if(!dfuBusy)throw new IllegalStateException("当前没有正在执行的升级");androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(DfuBaseService.BROADCAST_ACTION).putExtra(DfuBaseService.EXTRA_ACTION,action));log(action==DfuBaseService.ACTION_PAUSE?"已请求暂停 DFU":action==DfuBaseService.ACTION_RESUME?"已请求继续 DFU":"已请求停止 DFU");}
 private final DfuLogListener dfuLogListener=(address,level,message)->log("DFU "+message);
 private final DfuProgressListenerAdapter dfuListener=new DfuProgressListenerAdapter(){
  @Override public void onDeviceConnecting(String address){event("dfu",obj("state","正在连接升级设备","busy",true));}
  @Override public void onDfuProcessStarting(String address){event("dfu",obj("state","升级准备中","busy",true));}
  @Override public void onProgressChanged(String address,int percent,float speed,float average,int part,int total){dfuBusy=true;event("dfu",obj("state","正在传输固件","percent",percent,"speed",speed,"part",part,"total",total,"busy",true));}
  @Override public void onFirmwareValidating(String address){event("dfu",obj("state","设备正在验证固件","busy",true));}
  @Override public void onDfuCompleted(String address){dfuBusy=false;event("dfu",obj("state","升级完成","percent",100,"busy",false));log("DFU 升级完成");}
  @Override public void onDfuAborted(String address){dfuBusy=false;event("dfu",obj("state","升级已停止","busy",false));log("DFU 已停止");}
  @Override public void onError(String address,int error,int type,String message){dfuBusy=false;event("dfu",obj("state","升级失败："+message,"busy",false));log("DFU 错误 "+error+"："+message);}
 };
 private void runScript(JSONObject input)throws JSONException {JSONArray steps=input.getJSONArray("steps");if(steps.length()>100)throw new IllegalArgumentException("脚本最多 100 步");String address=input.getString("address");session(input);for(int i=0;i<steps.length();i++){JSONObject step=steps.getJSONObject(i);String action=step.getString("action");if(!Arrays.asList("read","write","notify","mtu","rssi","wait").contains(action))throw new IllegalArgumentException("脚本不允许操作："+action);if(action.equals("wait")&&(step.optInt("ms",1000)<0||step.optInt("ms",1000)>60000))throw new IllegalArgumentException("等待范围 0–60000 ms");}final int token=++runToken;scriptResults.clear();scriptStep(steps,0,address,token);}
 private void scriptStep(JSONArray steps,int i,String address,int token){if(token!=runToken)return;if(i>=steps.length()){event("script",obj("status","执行完毕：所有 GATT 步骤收到成功回调","results",new JSONArray(scriptResults)));return;}try{JSONObject a=new JSONObject(steps.getJSONObject(i).toString());String action=a.getString("action");event("script",obj("status","执行 "+(i+1)+"／"+steps.length()));if(action.equals("wait")){ui.postDelayed(()->scriptStep(steps,i+1,address,token),a.optInt("ms",1000));return;}Session s=sessions.get(address);if(s==null||!s.connected)throw new IOException("脚本设备已断开");if(s.active!=null||!s.queue.isEmpty()){ui.postDelayed(()->scriptStep(steps,i,address,token),100);return;}a.put("address",address);s.lastStatus=0;act(a);awaitScriptOperation(steps,i,address,token,SystemClock.elapsedRealtime());}catch(Exception e){runToken++;error(e);event("script",obj("status","执行停止："+e.getMessage()));}}
 private void awaitScriptOperation(JSONArray steps,int i,String address,int token,long start){if(token!=runToken)return;Session s=sessions.get(address);if(s==null||!s.connected||SystemClock.elapsedRealtime()-start>30000){runToken++;event("script",obj("status","执行停止：连接断开或超时"));return;}if(s.active!=null||!s.queue.isEmpty()){ui.postDelayed(()->awaitScriptOperation(steps,i,address,token,start),100);return;}if(s.lastStatus!=0){runToken++;event("script",obj("status","第 "+(i+1)+" 步失败，状态："+s.lastStatus));return;}scriptResults.add("第 "+(i+1)+" 步：Android GATT 成功回调");scriptStep(steps,i+1,address,token);}
}
