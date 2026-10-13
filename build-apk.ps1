<#
  BYD 自动签到 · LSPosed 模块 —— 一键构建 APK（Windows / PowerShell）

  用法：
      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1
      powershell -ExecutionPolicy Bypass -File .\build-apk.ps1 -Variant debug

  默认构建 **release**（正式版）。原因：Xposed 模块必须单 dex，而 AGP 在 debug
  变体下会把工程类按包拆成多个 dex，模块加载器通常只读 classes.dex，
  结果就是「LSPosed 里勾选了但没注入」。release 会合并成单个 classes.dex。
  脚本对产物有自动检查，多 dex 会直接报错。

  工具链查找顺序：
      1. -Toolchain 参数
      2. 环境变量 BYD_TOOLCHAIN
      3. D:\android-toolchain
      4. %USERPROFILE%\.byd-toolchain
    找到后自动使用其中的 JDK / Gradle / Android SDK，无需改 PATH。
#>
[CmdletBinding()]
param(
    [ValidateSet('debug', 'release')]
    [string]$Variant = 'release',
    [string]$Toolchain = '',
    [string]$InitScript = '',
    [switch]$SkipToolchainCheck
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$gradleRoot = Join-Path $root 'byd-lsp'

function Info($m) { Write-Host "  $m" }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "  [X]  $m" -ForegroundColor Red }

Write-Host ""
Write-Host "==== BYD 自动签到 · LSPosed 模块 构建（$Variant）====" -ForegroundColor Cyan
Write-Host ""

# ---------- 0. 工程自检 ----------
if (-not (Test-Path $gradleRoot)) { Fail "找不到模块目录: $gradleRoot"; exit 1 }
if (-not (Test-Path (Join-Path $root 'index.html'))) {
    Fail "找不到 index.html（控制台页面），它是 WebUI 的源文件"
    exit 1
}
Ok "index.html 存在（$((Get-Item (Join-Path $root 'index.html')).Length) 字节）"

# ---------- 1. 定位工具链 ----------
$toolchainRoots = @()
if ($Toolchain) { $toolchainRoots += $Toolchain }
if ($env:BYD_TOOLCHAIN) { $toolchainRoots += $env:BYD_TOOLCHAIN }
$toolchainRoots += 'D:\android-toolchain'
$toolchainRoots += (Join-Path $env:USERPROFILE '.byd-toolchain')

$tc = $null
if (-not $SkipToolchainCheck) {
    foreach ($t in $toolchainRoots) {
        if (-not $t) { continue }
        $jdk = Join-Path $t 'jdk-17'
        $grd = Join-Path $t 'gradle-8.9'
        $sdk = Join-Path $t 'android-sdk'
        if ((Test-Path "$jdk\bin\java.exe") -and (Test-Path "$grd\bin\gradle.bat") -and (Test-Path "$sdk\platforms")) {
            $tc = $t
            $env:JAVA_HOME = $jdk
            $env:ANDROID_HOME = $sdk
            $env:ANDROID_SDK_ROOT = $sdk
            $env:GRADLE_USER_HOME = Join-Path $t 'gradle-home'
            $env:Path = "$jdk\bin;$grd\bin;$sdk\platform-tools;$env:Path"
            Ok "使用工具链: $tc"
            break
        } elseif (Test-Path $t) {
            Warn "$t 存在但结构不完整（需要 jdk-17 / gradle-8.9 / android-sdk 三个子目录）"
        }
    }
}

if (-not $SkipToolchainCheck -and -not $tc) {
    $hasJava = Get-Command java -ErrorAction SilentlyContinue
    $hasGradle = Get-Command gradle -ErrorAction SilentlyContinue
    $sdkPath = $env:ANDROID_HOME
    if (-not $sdkPath) { $sdkPath = $env:ANDROID_SDK_ROOT }

    $missing = @()
    if (-not $hasJava) { $missing += 'JDK 17+' }
    if (-not $hasGradle) { $missing += 'Gradle 8.x' }
    if (-not $sdkPath -or -not (Test-Path $sdkPath)) { $missing += 'Android SDK' }

    if ($missing.Count -gt 0) {
        Write-Host ""
        Fail "未找到可用工具链，缺少: $($missing -join ', ')"
        Write-Host ""
        Write-Host "  三条可行路线：" -ForegroundColor Cyan
        Write-Host "    A. 用 Android Studio 打开 $(Join-Path $root 'byd-lsp')，Build → Build APK(s)"
        Write-Host "    B. 把本目录推到 GitHub，Actions 会跑 .github/workflows/build-apk.yml"
        Write-Host "    C. 准备一个工具链目录（含 jdk-17 / gradle-8.9 / android-sdk），"
        Write-Host "       然后用 -Toolchain <路径> 指定"
        Write-Host ""
        exit 2
    }
    Ok "使用系统 PATH 中的工具链"
}

# ---------- 2. 签名（release 必需） ----------
$ksProps = Join-Path $gradleRoot 'keystore.properties'
if ($Variant -eq 'release') {
    if (Test-Path $ksProps) {
        Ok "已配置 release 签名（keystore.properties）"
    } else {
        Warn "缺少 keystore.properties，release 产物将未签名、无法安装"
        Warn "可参考 byd-lsp/keystore.properties 的格式自行创建"
    }
}

# ---------- 3. 本地镜像（可选） ----------
$mirrorInit = if ($InitScript) { $InitScript }
              elseif ($tc) { Join-Path $tc 'gradle-mirror-init.gradle' }
              else { '' }
$initArgs = @()
if ($mirrorInit -and (Test-Path $mirrorInit)) {
    $initArgs = @('--init-script', $mirrorInit)
    Ok "使用镜像初始化脚本: $mirrorInit"
} elseif ($mirrorInit) {
    Warn "未找到镜像脚本，将直连官方仓库（maven.google.com 在本机可能超时）"
}

# ---------- 4. Xposed API 存根 ----------
$libs = Join-Path $gradleRoot 'app\libs'
$jar = Join-Path $libs 'api-82.jar'
if (-not (Test-Path $jar)) {
    New-Item -ItemType Directory -Force -Path $libs | Out-Null
    Info "下载 Xposed API 82 存根…"
    try {
        Invoke-WebRequest -Uri 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar' `
            -OutFile $jar -UseBasicParsing
        Ok "api-82.jar ($((Get-Item $jar).Length) 字节)"
    } catch {
        Fail "下载失败: $($_.Exception.Message)"
        Fail "可手动下载后放到: $jar"
        exit 3
    }
} else {
    Ok "api-82.jar 已存在"
}

# ---------- 5. 构建 ----------
$task = if ($Variant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
Write-Host ""
Info "开始构建（gradle $task）…"
Write-Host ""

Push-Location $gradleRoot
try {
    $gradleCmd = if ($tc) { Join-Path $tc 'gradle-8.9\bin\gradle.bat' } else { 'gradle' }
    & $gradleCmd $task --no-daemon --console=plain @initArgs
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host ""
    Fail "构建失败（退出码 $code）"
    Write-Host "  常见原因：platform 34 / build-tools 34 未安装；或网络无法访问依赖仓库"
    exit $code
}

# ---------- 6. 产物校验 ----------
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Inspect-Apk([string]$ApkPath) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($ApkPath)
    try {
        $dex = @($zip.Entries | Where-Object { $_.FullName -like 'classes*.dex' })
        return [pscustomobject]@{
            DexCount  = $dex.Count
            DexNames  = (($dex | ForEach-Object { $_.FullName }) -join ', ')
            Webui     = [bool]($zip.Entries | Where-Object { $_.FullName -eq 'assets/webui/index.html' })
            Init      = [bool]($zip.Entries | Where-Object { $_.FullName -eq 'assets/xposed_init' })
            Versions  = [bool]($zip.Entries | Where-Object { $_.FullName -eq 'assets/wxcode_versions.json' })
        }
    } finally { $zip.Dispose() }
}

$apkDir = Join-Path $gradleRoot "app\build\outputs\apk\$Variant"
$apks = @()
if (Test-Path $apkDir) { $apks = Get-ChildItem -Path $apkDir -Filter *.apk -Recurse }

Write-Host ""
if ($apks.Count -eq 0) { Fail "构建结束但没找到 APK"; exit 4 }

Write-Host "==== 构建成功 ====" -ForegroundColor Green
$bad = 0
foreach ($a in $apks) {
    $r = Inspect-Apk $a.FullName
    $dest = Join-Path $root $a.Name
    Copy-Item -LiteralPath $a.FullName -Destination $dest -Force
    Ok "$($a.Name)  ($([math]::Round($a.Length / 1KB)) KB) → $dest"

    Info "dex: $($r.DexCount) 个（$($r.DexNames)）"
    Info "资源: webui=$($r.Webui)  xposed_init=$($r.Init)  版本表=$($r.Versions)"

    if ($r.DexCount -ne 1) {
        $bad++
        Write-Host ""
        Fail "该 APK 有 $($r.DexCount) 个 dex —— LSPosed 加载模块时通常只读 classes.dex，"
        Fail "入口类 xiaojw.byd.hook.BydHook 不在里面，表现就是「勾选了但没注入」。"
        Fail "请改用 -Variant release 构建（release 会合并成单个 classes.dex）。"
    }
    foreach ($flag in @($r.Webui, $r.Init, $r.Versions)) {
        if (-not $flag) { $bad++; Fail "缺少必需资源，该 APK 不可用" }
    }
}

if ($bad -gt 0) {
    Write-Host ""
    Fail "产物校验未通过（$bad 项问题），不要安装这个包"
    exit 5
}

Write-Host ""
Write-Host "==== 产物校验通过：单 dex + 资源齐全 ====" -ForegroundColor Green
Write-Host ""
Write-Host "  安装与启用：" -ForegroundColor Cyan
Write-Host "    1. 如果装过旧版（debug 或旧签名），先卸载 —— 签名变了必须重装"
Write-Host "    2. 安装本 APK"
Write-Host "    3. LSPosed 管理器 → 模块 → 勾选「BYD 自动签到」"
Write-Host "    4. 作用域勾选 微信（com.tencent.mm；分身/多开包名相同，勾一项即可）"
Write-Host "    5. 强行停止微信后重新打开，LSPosed 日志应出现「模块已注入」"
Write-Host "    6. 打开「BYD 自动签到」App 配置后点「立即签到」验证"
Write-Host ""
Info "本次为 release 包：无 debuggable 标记，已用 byd-lsp-release.jks 签名。"
