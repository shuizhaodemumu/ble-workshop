package com.codex.bleworkshop;
import org.json.*;
public final class Core {
    static { System.loadLibrary("ble_core"); }
    public static native String call(String operation, String input);
    public static JSONObject run(String operation, String input) {
        try {
            JSONObject envelope = new JSONObject(call(operation, input));
            if (!envelope.optBoolean("ok")) throw new IllegalArgumentException(envelope.optString("error", "核心处理失败"));
            return envelope.getJSONObject("result");
        } catch (JSONException e) { throw new IllegalStateException("核心返回格式无效", e); }
    }
    public static byte[] bytes(String text, boolean utf8) {
        JSONArray a = run(utf8 ? "text" : "hex", text).optJSONArray("bytes");
        byte[] b = new byte[a.length()]; for (int i=0;i<b.length;i++) b[i]=(byte)a.optInt(i); return b;
    }
    public static String hex(byte[] value) { if(value==null)return "";StringBuilder s=new StringBuilder();for(byte b:value){if(s.length()>0)s.append(' ');s.append(String.format(java.util.Locale.ROOT,"%02X",b&255));}return s.toString(); }
    public static java.util.UUID uuid(String text) {return java.util.UUID.fromString(run("uuid",text).optString("uuid"));}
    public static String name(java.util.UUID uuid) {return run("uuid",uuid.toString()).optString("name");}
    public static String decode(java.util.UUID uuid, byte[] value) {
        try {JSONObject o=new JSONObject();o.put("uuid",uuid.toString());o.put("hex",hex(value));JSONObject r=run("decode",o.toString());StringBuilder s=new StringBuilder(r.optString("hex"));JSONArray n=r.optJSONArray("notes");for(int i=0;i<n.length();i++)s.append("\n").append(n.optString(i));return s.toString();}catch(JSONException e){return hex(value);}
    }
}
