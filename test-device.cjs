const {chromium}=require('D:/codex create/REA/node_modules/rea-agents/node_modules/playwright-core');
const assert=require('assert'),fs=require('fs'),path=require('path');
(async()=>{
 const browser=await chromium.connectOverCDP('http://127.0.0.1:9222');
 const page=browser.contexts()[0].pages().find(p=>p.url().includes('ble-workshop.local'));
 assert(page,'Expected app WebView');const errors=[];page.on('pageerror',e=>errors.push(e.message));
 const result=await page.evaluate(()=>{
  const hex=JSON.parse(Native.core('hex','01 FF'));const crc=JSON.parse(Native.core('hex','31 32 33 34 35 36 37 38 39'));const name=JSON.parse(Native.core('uuid','180F'));const bad=JSON.parse(Native.core('hex','GG'));const ad=JSON.parse(Native.core('advertisement','02 01 06 03 03 0F 18'));const utf=JSON.parse(Native.core('text','中文测试'));
  return {hex,crc,name,bad,ad,utf};
 });
 assert.deepEqual(result.hex.result.bytes,[1,255]);assert.equal(result.crc.result.crc32,'CBF43926');assert.equal(result.name.result.name,'电池');assert.equal(result.bad.ok,false);assert.equal(result.ad.result.fields.length,2);assert.equal(result.utf.result.length,12);
 await page.evaluate(()=>{tab('scan');devices={};send('scan',{mode:2,seconds:10,uuid:''})});
 await page.waitForFunction(()=>scanning===true,{timeout:5000});await page.waitForTimeout(6500);
 const count=await page.evaluate(()=>Object.keys(devices).length);
 await page.evaluate(()=>{if(scanning)send('scan')});await page.waitForFunction(()=>scanning===false);
 const service='7d273000-46a4-4e70-b7ce-1591f0b17f01';
 await page.evaluate(s=>{tab('advertise');$('serverService').value=s;$('serverCharacteristic').value='7d273001-46a4-4e70-b7ce-1591f0b17f01';$('manufacturerData').value='';advertise()},service);
 await page.waitForFunction(()=>advertising===true,{timeout:5000});await page.waitForTimeout(1000);
 await page.evaluate(()=>send('serverValue',{value:'01 FF'}));await page.waitForTimeout(300);
 await page.evaluate(()=>send('advertise'));await page.waitForFunction(()=>advertising===false);
 await page.evaluate(()=>{tab('scan');renderDevices()});
 await page.screenshot({path:path.resolve(__dirname,'../../outputs/蓝牙工坊_真机界面.png')});
 const nativeLogs=await page.evaluate(()=>logLines.filter(l=>/Rust|扫描|广播|服务端|错误/.test(l)).slice(-30));
 const report={test:'Android real device',sdk:36,abi:'arm64-v8a',rustJniChecks:6,scanCount:count,advertiseStarted:true,advertiseStopped:true,serverValueUpdateSubmitted:true,pageErrors:errors,limitations:['GATT client read/write/notify requires an explicitly selected owned peripheral','Server peer read/write/notify not yet tested','DFU not flashed; requires matching firmware and target'],logs:nativeLogs};
 fs.writeFileSync(path.join(__dirname,'build/device-test.json'),JSON.stringify(report,null,2));
 assert.deepEqual(errors,[]);console.log('DEVICE_SMOKE_PASS: real Android Rust JNI (6 checks), BLE scan ('+count+' results), advertising start/stop; no remote peripheral write or firmware flash');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
