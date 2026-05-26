# 编译可安装 APK 版本 Spec

## Why
当前项目 Hanako 是一个 Android Kotlin Compose 应用，但环境中没有 Android SDK，也没有 release 签名密钥库，无法直接编译出可安装的 APK。需要搭建完整的构建环境并生成可安装的 APK 文件。

## What Changes
- 安装 Android SDK（commandlinetools），配置 compileSdk 36、build-tools 等必要组件
- 配置 `local.properties` 指向 SDK 路径
- 生成自签名密钥库（keystore），创建 `keystore.properties` 配置文件
- 执行 Gradle 构建，生成 release APK
- 验证 APK 文件可正常安装（通过 `aapt dump badging` 验证签名和包信息）

## Impact
- Affected code: `local.properties`（新建）、`keystore.properties`（新建）、`hanako-release.keystore`（新建）
- 不修改任何现有源代码

## ADDED Requirements

### Requirement: Android SDK 环境
系统 SHALL 安装 Android SDK commandlinetools，并通过 sdkmanager 安装以下组件：
- `platforms;android-36`
- `build-tools;36.0.0`（或最新可用版本）
- `platform-tools`

系统 SHALL 配置 `ANDROID_HOME` 环境变量和 `local.properties` 文件指向 SDK 安装路径。

#### Scenario: SDK 安装成功
- **WHEN** 执行 sdkmanager 安装命令
- **THEN** `platforms/android-36/` 和 `build-tools/` 目录存在

### Requirement: Release 签名密钥库
系统 SHALL 生成一个自签名的 RSA 密钥库文件用于 release APK 签名，并创建对应的 `keystore.properties` 配置文件。

#### Scenario: 密钥库生成成功
- **WHEN** 执行 keytool 生成密钥库命令
- **THEN** `keystore.properties` 文件存在且包含 storeFile、storePassword、keyAlias、keyPassword 四个属性
- **THEN** `.keystore` 文件存在且可被 Gradle 读取

### Requirement: 可安装 APK 构建
系统 SHALL 通过 Gradle 执行 `assembleRelease` 任务，生成已签名、可安装的 release APK 文件。

#### Scenario: APK 构建成功
- **WHEN** 执行 `./gradlew assembleRelease`
- **THEN** `app/build/outputs/apk/release/app-release.apk` 文件存在
- **THEN** APK 文件大小 > 0

#### Scenario: APK 签名验证
- **WHEN** 使用 `apksigner verify` 检查 APK
- **THEN** APK 签名验证通过

## MODIFIED Requirements
无

## REMOVED Requirements
无
