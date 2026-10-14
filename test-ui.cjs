const {chromium}=require('D:/codex create/REA/node_modules/rea-agents/node_modules/playwright-core');
const path=require('path'),fs=require('fs'),assert=require('assert');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-first-run','--disable-background-networking']});
 const page=await browser.newPage({viewport:{width:412,height:915},deviceScaleFactor:1});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('file:///'+path.join(__dirname,'app/assets/index.html').replaceAll('\\','/'));
 await page.evaluate(()=>{
  window.Native={send(s){window.sent=JSON.parse(s)},core(){return JSON.stringify({ok:true,result:{bytes:[1,2],length:2,uuid:'0000180f-0000-1000-8000-00805f9b34fb'}})}};
  window.onNative({type:'device',data:{address:'AA:BB:CC:DD:EE:01',name:'温度传感器 <demo>',rssi:-54,connectable:true,bonded:false,time:Date.now(),phy:1,advertisement:{raw:'02 01 06 03 03 0F 18',fields:[{type:3,name:'16 位服务 UUID',value:'电池服务'}],errors:[]}}});
  renderDevices();
 });
 assert.equal(await page.locator('.deviceName').textContent(),'温度传感器 <demo>');
 assert.equal(await page.locator('body').evaluate(e=>e.scrollWidth<=innerWidth),true,'mobile overflow');
 await page.getByRole('button',{name:'连接',exact:true}).first().click();assert.equal(await page.evaluate(()=>sent.action),'connect');
 await page.evaluate(()=>{onNative({type:'services',data:{address:'AA:BB:CC:DD:EE:01',mtu:247,services:[{uuid:'0000180f-0000-1000-8000-00805f9b34fb',name:'电池',primary:true,characteristics:[{key:'battery/key',uuid:'00002a19-0000-1000-8000-00805f9b34fb',name:'电池电量',properties:18,descriptors:[]}]}]}})});
 await page.getByRole('button',{name:'读取',exact:true}).click();assert.equal(await page.evaluate(()=>sent.action),'read');assert.equal(await page.evaluate(()=>sent.key),'battery/key');
 await page.getByRole('button',{name:'订阅',exact:true}).click();assert.equal(await page.evaluate(()=>sent.action),'notify');assert.equal(await page.evaluate(()=>sent.enabled),true);
 await page.evaluate(()=>{onNative({type:'subscription',data:{address:'AA:BB:CC:DD:EE:01',key:'battery/key',enabled:true,status:0}});onNative({type:'value',data:{address:'AA:BB:CC:DD:EE:01',key:'battery/key',decoded:'57\n电池电量：87%'}})});
 assert.equal(await page.locator('.value').textContent(),'57\n电池电量：87%');
 await page.evaluate(()=>tab('dfu'));await page.fill('#dfuAddress','AA:BB:CC:DD:EE:01');await page.getByRole('button',{name:'选择固件文件',exact:true}).click();assert.equal(await page.evaluate(()=>sent.action),'firmware');
 assert.equal(await page.locator('#dfuStart').isDisabled(),true);
 await page.evaluate(()=>onNative({type:'firmware',data:{format:'zip',bytes:1234,sha256:'a'.repeat(64)}}));assert.equal(await page.locator('#dfuStart').isDisabled(),false);
 await page.evaluate(()=>tab('advertise'));await page.getByRole('button',{name:'启动广播和服务端'}).click();assert.equal(await page.evaluate(()=>sent.action),'advertise');
 await page.evaluate(()=>tab('tools'));await page.fill('#scriptInput','[{"action":"write","key":"battery/key","value":"01"}]');await page.getByRole('button',{name:'执行脚本'}).click();assert.equal(await page.locator('#sheet').evaluate(e=>e.classList.contains('open')),true,'script writes require confirmation');
 await page.evaluate(()=>closeSheet());
 for(const name of ['scan','gatt','advertise','dfu','tools']){await page.evaluate(n=>tab(n),name);assert.equal(await page.locator('body').evaluate(e=>e.scrollWidth<=innerWidth),true,'overflow '+name);}
 const out=path.resolve(__dirname,'../../outputs');fs.mkdirSync(out,{recursive:true});
 await page.evaluate(()=>{tab('scan');$('permissionNotice').style.display='none';$('statusText').textContent='界面示例 · 并非真实蓝牙扫描';});await page.screenshot({path:path.join(out,'蓝牙工坊_界面预览.png'),fullPage:false});
 assert.deepEqual(errors,[]);await browser.close();console.log('UI_SMOKE_PASS: mobile layout, XSS escaping, GATT controls, DFU guard, advertiser, script confirmation; mock Native bridge only');
})().catch(e=>{console.error(e);process.exit(1)});
