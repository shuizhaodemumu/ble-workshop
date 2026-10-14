use jni::{
    objects::{JClass, JString},
    sys::jstring,
    JNIEnv,
};
use serde_json::{json, Value};

pub fn parse_hex(text: &str) -> Result<Vec<u8>, String> {
    if text.len() > 16384 {
        return Err("输入过长（最多 4096 字节）".into());
    }
    let compact: String = text
        .chars()
        .filter(|c| !c.is_whitespace() && *c != ':' && *c != '-')
        .collect();
    let compact = compact
        .strip_prefix("0x")
        .or_else(|| compact.strip_prefix("0X"))
        .unwrap_or(&compact);
    if !compact.len().is_multiple_of(2) {
        return Err("十六进制必须是完整字节，例如 01 AF 20".into());
    }
    if !compact.is_ascii() || !compact.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err("只能输入 0–9、A–F 和分隔符".into());
    }
    if compact.len() > 8192 {
        return Err("输入过长（最多 4096 字节）".into());
    }
    (0..compact.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&compact[i..i + 2], 16).map_err(|_| "无效十六进制".into()))
        .collect()
}
pub fn hex(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|b| format!("{b:02X}"))
        .collect::<Vec<_>>()
        .join(" ")
}
pub fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0u32;
    for b in bytes {
        crc ^= *b as u32;
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xEDB88320u32 & (0u32.wrapping_sub(crc & 1)));
        }
    }
    !crc
}
pub fn uuid_name(input: &str) -> String {
    let lower = input.trim().to_ascii_lowercase();
    let short = if lower.len() == 36
        && lower.ends_with("-0000-1000-8000-00805f9b34fb")
        && lower.starts_with("0000")
    {
        &lower[4..8]
    } else {
        lower.as_str()
    };
    let name = match short {
        "1800" => "通用访问",
        "1801" => "通用属性",
        "1802" => "即时警报",
        "1803" => "链路丢失",
        "1804" => "发射功率",
        "1805" => "当前时间",
        "1808" => "血糖",
        "1809" => "健康温度计",
        "180a" => "设备信息",
        "180d" => "心率",
        "180f" => "电池",
        "1810" => "血压",
        "1811" => "警报通知",
        "1812" => "人机接口 HID",
        "1813" => "扫描参数",
        "1814" => "跑步速度与步频",
        "1816" => "骑行速度与踏频",
        "1818" => "骑行功率",
        "181a" => "环境感测",
        "181b" => "人体成分",
        "181c" => "用户数据",
        "181d" => "体重秤",
        "1820" => "互联网协议支持",
        "1822" => "脉搏血氧仪",
        "1826" => "健身器材",
        "1827" => "Mesh 配网",
        "1828" => "Mesh 代理",
        "fe59" => "Nordic 安全 DFU",
        "1530" => "Nordic 传统 DFU",
        "2a00" => "设备名称",
        "2a01" => "外观",
        "2a04" => "首选连接参数",
        "2a05" => "服务变更",
        "2a19" => "电池电量",
        "2a23" => "系统 ID",
        "2a24" => "型号",
        "2a25" => "序列号",
        "2a26" => "固件版本",
        "2a27" => "硬件版本",
        "2a28" => "软件版本",
        "2a29" => "制造商",
        "2a37" => "心率测量",
        "2a38" => "心率传感器位置",
        "2a39" => "心率控制点",
        "2a6e" => "温度",
        "2a6f" => "湿度",
        "2900" => "扩展属性",
        "2901" => "用户描述",
        "2902" => "客户端特征配置 CCCD",
        "2904" => "显示格式",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" => "Nordic UART 服务",
        "6e400002-b5a3-f393-e0a9-e50e24dcca9e" => "UART 接收 RX",
        "6e400003-b5a3-f393-e0a9-e50e24dcca9e" => "UART 发送 TX",
        _ => "自定义 UUID",
    };
    name.into()
}
pub fn canonical_uuid(text: &str) -> Result<String, String> {
    let text = text.trim().to_ascii_lowercase();
    if (text.len() == 4 || text.len() == 8) && text.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Ok(format!("{:0>8}-0000-1000-8000-00805f9b34fb", text));
    }
    if text.len() == 36
        && text.bytes().enumerate().all(|(i, c)| {
            if [8, 13, 18, 23].contains(&i) {
                c == b'-'
            } else {
                c.is_ascii_hexdigit()
            }
        })
    {
        return Ok(text);
    }
    Err("UUID 需要 4／8 位十六进制或标准 128 位形式".into())
}
pub fn advertisement(bytes: &[u8]) -> Value {
    let mut fields = Vec::new();
    let mut errors = Vec::new();
    let mut pos = 0;
    while pos < bytes.len() {
        let len = bytes[pos] as usize;
        if len == 0 {
            break;
        }
        if pos + 1 + len > bytes.len() {
            errors.push(format!("偏移 {pos}：广播字段被截断"));
            break;
        }
        let kind = bytes[pos + 1];
        let data = &bytes[pos + 2..pos + 1 + len];
        let (name, value) = match kind {
            1 => (
                "广播标志",
                data.first()
                    .map(|v| {
                        format!(
                            "0x{v:02X} · {} · {}",
                            if v & 2 != 0 {
                                "通用可发现"
                            } else {
                                "非通用可发现"
                            },
                            if v & 4 != 0 {
                                "仅 BLE"
                            } else {
                                "支持经典蓝牙"
                            }
                        )
                    })
                    .unwrap_or_else(|| "空标志".into()),
            ),
            8 | 9 => (
                if kind == 8 {
                    "短设备名称"
                } else {
                    "完整设备名称"
                },
                String::from_utf8_lossy(data).into(),
            ),
            10 => (
                "发射功率",
                data.first()
                    .map(|v| format!("{} dBm", *v as i8))
                    .unwrap_or_default(),
            ),
            2 | 3 | 20 => (
                "16 位服务 UUID",
                data.as_chunks::<2>()
                    .0
                    .iter()
                    .map(|c| {
                        let u = format!("{:04x}", u16::from_le_bytes([c[0], c[1]]));
                        format!("0x{u} {}", uuid_name(&u))
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
            ),
            4 | 5 => (
                "32 位服务 UUID",
                data.as_chunks::<4>()
                    .0
                    .iter()
                    .map(|c| format!("{:08X}", u32::from_le_bytes(*c)))
                    .collect::<Vec<_>>()
                    .join("\n"),
            ),
            6 | 7 | 21 => (
                "128 位服务 UUID",
                data.as_chunks::<16>()
                    .0
                    .iter()
                    .map(|c| {
                        let mut r = c.to_vec();
                        r.reverse();
                        let s = r.iter().map(|b| format!("{b:02x}")).collect::<String>();
                        format!(
                            "{}-{}-{}-{}-{}",
                            &s[..8],
                            &s[8..12],
                            &s[12..16],
                            &s[16..20],
                            &s[20..]
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("\n"),
            ),
            22 if data.len() >= 2 => (
                "16 位服务数据",
                format!(
                    "{} · {}",
                    uuid_name(&format!("{:04x}", u16::from_le_bytes([data[0], data[1]]))),
                    hex(&data[2..])
                ),
            ),
            25 if data.len() >= 2 => (
                "外观",
                format!("0x{:04X}", u16::from_le_bytes([data[0], data[1]])),
            ),
            255 if data.len() >= 2 => {
                let id = u16::from_le_bytes([data[0], data[1]]);
                (
                    "制造商数据",
                    format!(
                        "厂商 0x{id:04X} {}\n{}",
                        match id {
                            0x59 => "Nordic Semiconductor",
                            0x4c => "Apple",
                            0x6 => "Microsoft",
                            0x75 => "Samsung",
                            _ => "",
                        },
                        hex(&data[2..])
                    ),
                )
            }
            _ => ("广播数据", hex(data)),
        };
        let width = match kind {
            2 | 3 | 20 => 2,
            4 | 5 => 4,
            6 | 7 | 21 => 16,
            _ => 1,
        };
        if !data.len().is_multiple_of(width) {
            errors.push(format!("偏移 {pos}：UUID 字段长度不完整"));
        }
        fields.push(json!({"type":kind,"name":name,"value":value,"hex":hex(data),"offset":pos}));
        pos += len + 1;
    }
    json!({"fields":fields,"errors":errors,"raw":hex(bytes)})
}
pub fn decode_value(uuid: &str, bytes: &[u8]) -> Value {
    let mut notes = Vec::new();
    let uuid = uuid.to_ascii_lowercase();
    if uuid.starts_with("00002a19-") && bytes.len() == 1 {
        notes.push(format!("电池电量：{}%", bytes[0]));
    }
    if uuid.starts_with("00002a37-") && bytes.len() >= 2 {
        let pulse = if bytes[0] & 1 == 0 {
            Some(bytes[1] as u16)
        } else if bytes.len() >= 3 {
            Some(u16::from_le_bytes([bytes[1], bytes[2]]))
        } else {
            None
        };
        if let Some(v) = pulse {
            notes.push(format!("心率：{v} 次／分"));
        }
    }
    if uuid.starts_with("00002a6e-") && bytes.len() >= 2 {
        notes.push(format!(
            "温度：{:.2} ℃",
            i16::from_le_bytes([bytes[0], bytes[1]]) as f32 / 100.
        ));
    }
    if uuid.starts_with("00002a6f-") && bytes.len() >= 2 {
        notes.push(format!(
            "湿度：{:.2}%",
            u16::from_le_bytes([bytes[0], bytes[1]]) as f32 / 100.
        ));
    }
    if let Ok(s) = std::str::from_utf8(bytes) {
        if s.chars()
            .all(|c| !c.is_control() || c == '\n' || c == '\r' || c == '\t')
        {
            notes.push(format!("UTF-8：{s}"));
        }
    }
    if !bytes.is_empty() && bytes.len() <= 8 {
        let mut a = [0u8; 8];
        a[..bytes.len()].copy_from_slice(bytes);
        notes.push(format!("无符号小端整数：{}", u64::from_le_bytes(a)));
    }
    json!({"hex":hex(bytes),"length":bytes.len(),"notes":notes,"crc32":format!("{:08X}",crc32(bytes))})
}
pub fn dispatch(op: &str, input: &str) -> Value {
    let result: Result<Value, String> = (|| match op {
        "hex" => {
            let b = parse_hex(input)?;
            Ok(
                json!({"bytes":b,"hex":hex(&b),"length":b.len(),"crc32":format!("{:08X}",crc32(&b))}),
            )
        }
        "text" => {
            if input.len() > 4096 {
                return Err("UTF-8 文本最多 4096 字节".into());
            }
            let b = input.as_bytes();
            Ok(json!({"bytes":b,"hex":hex(b),"length":b.len()}))
        }
        "advertisement" => Ok(advertisement(&parse_hex(input)?)),
        "uuid" => {
            let u = canonical_uuid(input)?;
            Ok(json!({"uuid":u,"name":uuid_name(&u)}))
        }
        "decode" => {
            let v: Value = serde_json::from_str(input).map_err(|_| "无效 JSON")?;
            let u = v["uuid"].as_str().ok_or("缺少 uuid")?;
            let b = parse_hex(v["hex"].as_str().ok_or("缺少 hex")?)?;
            Ok(decode_value(u, &b))
        }
        _ => Err("未知核心操作".into()),
    })();
    match result {
        Ok(v) => json!({"ok":true,"result":v}),
        Err(e) => json!({"ok":false,"error":e}),
    }
}
#[no_mangle]
pub extern "system" fn Java_com_codex_bleworkshop_Core_call(
    mut env: JNIEnv,
    _: JClass,
    operation: JString,
    input: JString,
) -> jstring {
    let result = (|| {
        let op: String = env.get_string(&operation)?.into();
        let text: String = env.get_string(&input)?.into();
        Ok::<_, jni::errors::Error>(dispatch(&op, &text).to_string())
    })();
    let text =
        result.unwrap_or_else(|_| json!({"ok":false,"error":"JNI 输入读取失败"}).to_string());
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hex_roundtrip() {
        for bytes in [vec![], vec![0, 1, 127, 128, 255], (0..=255).collect()] {
            assert_eq!(parse_hex(&hex(&bytes)).unwrap(), bytes);
        }
    }
    #[test]
    fn reject_invalid() {
        for s in ["A", "GG", "😺", "０１", "AAxB"] {
            assert!(parse_hex(s).is_err(), "{s}");
        }
    }
    #[test]
    fn hex_formats() {
        assert_eq!(parse_hex("0x01:Af-20\n").unwrap(), [1, 175, 32]);
    }
    #[test]
    fn crc_known_vector() {
        assert_eq!(crc32(b"123456789"), 0xcbf43926);
        assert_eq!(crc32(&[]), 0);
    }
    #[test]
    fn little_endian_service() {
        let v = advertisement(&[3, 3, 0x0f, 0x18]);
        assert!(v["fields"][0]["value"].as_str().unwrap().contains("电池"));
    }
    #[test]
    fn truncated_ad_is_reported() {
        assert_eq!(
            advertisement(&[5, 9, 65])["errors"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
    }
    #[test]
    fn ad_never_panics() {
        for kind in 0..=255 {
            for n in 0..64 {
                let mut b = vec![n as u8, kind];
                b.resize(66, 255);
                let _ = advertisement(&b);
            }
        }
    }
    #[test]
    fn uuid_canonicalization() {
        assert_eq!(
            canonical_uuid("180F").unwrap(),
            "0000180f-0000-1000-8000-00805f9b34fb"
        );
        assert!(canonical_uuid("123").is_err());
        assert_eq!(uuid_name("0000180f-0000-1000-8000-00805f9b34fb"), "电池");
    }
    #[test]
    fn battery_temperature() {
        assert!(
            decode_value("00002a19-0000-1000-8000-00805f9b34fb", &[87])["notes"][0]
                .as_str()
                .unwrap()
                .contains("87%")
        );
        assert!(decode_value(
            "00002a6e-0000-1000-8000-00805f9b34fb",
            &(-125i16).to_le_bytes()
        )["notes"][0]
            .as_str()
            .unwrap()
            .contains("-1.25"));
    }
    #[test]
    fn dispatcher_errors_are_json() {
        assert_eq!(dispatch("decode", "{}")["ok"], false);
        assert_eq!(dispatch("unknown", "")["ok"], false);
        assert_eq!(dispatch("hex", "FF")["result"]["bytes"][0], 255);
    }
}
