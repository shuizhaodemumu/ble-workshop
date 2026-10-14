# 第三方组件

- Nordic Android DFU Library 2.11.0：BSD-3-Clause。https://github.com/NordicSemiconductor/Android-DFU-Library
- AndroidX core / lifecycle / annotation / localbroadcastmanager / versionedparcelable / collection / concurrent / arch：Apache-2.0。https://android.googlesource.com/platform/frameworks/support/
- Kotlin stdlib 2.3.10、kotlinx.coroutines 1.7.3：Apache-2.0。https://github.com/JetBrains/kotlin https://github.com/Kotlin/kotlinx.coroutines
- Gson 2.13.2、Guava listenablefuture 1.0：Apache-2.0。https://github.com/google/gson https://github.com/google/guava
- Rust `jni` 0.21.1：Apache-2.0 / MIT。https://github.com/jni-rs/jni-rs
- Rust `serde_json` 1.0.154：Apache-2.0 / MIT。https://github.com/serde-rs/json
- Rust 传递依赖精确版本见 `core/Cargo.lock`。

Android 构建依赖版本清单附于 `tool-bootstrap/versions.json`。原始第三方 archive 中的 license/notice 随源码包 `third-party-licenses` 保留。未包含原版 nRF Connect 的代码、品牌图标或用户固件。
