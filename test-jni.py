import pathlib,subprocess
root=pathlib.Path(__file__).resolve().parent;tools=root.parent/'android-tools';build=root/'build/jni-test';build.mkdir(parents=True,exist_ok=True)
jdk=tools/'jdk/jdk-17.0.20.1+1'
source=build/'Core.java'
source.write_text('''package com.codex.bleworkshop;
public final class Core {
 static {System.load(System.getProperty("ble.library"));}
 public static native String call(String op,String input);
 public static void main(String[] args) {
  String[] ops={"hex","hex","uuid","advertisement","decode","text"};
  String[] in={"01 FF","GZ","180F","03 03 0F 18","{\\"uuid\\":\\"00002a19-0000-1000-8000-00805f9b34fb\\",\\"hex\\":\\"57\\"}","中文测试"};
  String[] expected={"[1,255]","\\"ok\\":false","电池","电池","87%","中文测试"};
  for(int i=0;i<ops.length;i++){String r=call(ops[i],in[i]);if(i==5)r=call("decode","{\\"uuid\\":\\"FFF1\\",\\"hex\\":\\""+r.split("\\"hex\\":\\"")[1].split("\\"")[0]+"\\"}");if(!r.contains(expected[i]))throw new AssertionError(ops[i]+": "+r);}
  System.out.println("JNI_SMOKE_PASS: 6 real JVM-to-Rust calls incl UTF-8 and error responses");
 }
}''',encoding='utf8')
subprocess.run([str(jdk/'bin/javac.exe'),'-encoding','UTF-8','-d',str(build),str(source)],check=True)
subprocess.run([str(jdk/'bin/java.exe'),'-Dfile.encoding=UTF-8','-Dble.library='+str(root/'core/target/debug/ble_core.dll'),'-cp',str(build),'com.codex.bleworkshop.Core'],check=True)
