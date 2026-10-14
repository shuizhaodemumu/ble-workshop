param([ValidateSet('test','android','host','lint')][string]$Mode='test')
$ErrorActionPreference='Stop'
$workspace=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$rustRoot='D:\codex create\驱动总裁Rust重构'
$createdV=$false;$createdX=$false
if(-not(Test-Path 'V:\')){subst V: $rustRoot;$createdV=$true}
if(-not(Test-Path 'X:\')){subst X: $workspace;$createdX=$true}
try {
 Add-Type -TypeDefinition 'using System; using System.Text; using System.Runtime.InteropServices; public class BleDrive { [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] public static extern uint QueryDosDevice(string n,StringBuilder s,int len); }' -ErrorAction SilentlyContinue
 $vMap=[Text.StringBuilder]::new(1024);$xMap=[Text.StringBuilder]::new(1024)
 [void][BleDrive]::QueryDosDevice('V:',$vMap,1024);[void][BleDrive]::QueryDosDevice('X:',$xMap,1024)
 if($vMap.ToString() -ne ('\??\'+$rustRoot) -or $xMap.ToString() -ne ('\??\'+$workspace)){throw '盘符映射与预期目录不符'}
 $env:RUSTUP_HOME='V:\work\rust-tools\rustup';$env:CARGO_HOME='V:\work\rust-tools\cargo'
 $env:PATH='V:\work\rust-tools\rustup\toolchains\stable-x86_64-pc-windows-gnu\bin;V:\work\rust-tools\mingw\mingw64\bin;'+$env:PATH
 $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER='V:\work\ui-reference\gcc-linker.cmd'
 $env:https_proxy='http://127.0.0.1:7890'
 $env:CARGO_ENCODED_RUSTFLAGS=$null
 $env:RUSTFLAGS='--sysroot=V:\work\rust-tools\rustup\toolchains\stable-x86_64-pc-windows-gnu -C link-arg=-specs=V:\work\mingw-endfile.specs'
 $manifest='X:\work\android-source\core\Cargo.toml'
 if($Mode -eq 'test'){cargo test --locked --manifest-path $manifest}
 elseif($Mode -eq 'host'){cargo build --locked --manifest-path $manifest}
 elseif($Mode -eq 'lint'){cargo fmt --manifest-path $manifest --check;if($LASTEXITCODE -ne 0){throw 'fmt failed'};cargo clippy --locked --manifest-path $manifest --all-targets -- -D warnings}
 else {
  $env:RUSTFLAGS=$null
  $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER='X:\work\android-source\host-linker.cmd'
  $env:CARGO_ENCODED_RUSTFLAGS=@('-C','linker-flavor=ld.lld','-C','linker=V:\work\rust-tools\rustup\toolchains\stable-x86_64-pc-windows-gnu\lib\rustlib\x86_64-pc-windows-gnu\bin\rust-lld.exe','-L','X:\work\android-tools\ndk\android-ndk-r27c\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\lib\aarch64-linux-android\26','-L','X:\work\android-tools\native-libs','-C','link-arg=-z','-C','link-arg=max-page-size=16384') -join [char]31
  cargo build --locked --manifest-path $manifest --release --target aarch64-linux-android
 }
 if($LASTEXITCODE -ne 0){throw 'Rust 构建或测试失败'}
} finally {if($createdX){subst X: /D};if($createdV){subst V: /D}}
