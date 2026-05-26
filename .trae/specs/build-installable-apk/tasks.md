# Tasks

- [x] Task 1: 安装 Android SDK 环境
  - [x] SubTask 1.1: 下载 Android SDK commandlinetools
  - [x] SubTask 1.2: 使用 sdkmanager 安装 platforms;android-36、build-tools、platform-tools
  - [x] SubTask 1.3: 配置 ANDROID_HOME 环境变量
  - [x] SubTask 1.4: 创建 local.properties 文件指向 SDK 路径
  - [x] SubTask 1.5: 接受 SDK licenses

- [x] Task 2: 生成 Release 签名密钥库
  - [x] SubTask 2.1: 使用 keytool 生成自签名 RSA 密钥库
  - [x] SubTask 2.2: 创建 keystore.properties 配置文件

- [x] Task 3: 构建 Release APK
  - [x] SubTask 3.1: 执行 ./gradlew assembleRelease
  - [x] SubTask 3.2: 验证 APK 文件生成成功

- [x] Task 4: 验证 APK 可安装性
  - [x] SubTask 4.1: 使用 apksigner verify 验证 APK 签名
  - [x] SubTask 4.2: 使用 aapt dump badging 验证包信息

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1, Task 2]
- [Task 4] depends on [Task 3]
