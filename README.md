# 同屏自在听 Demo

Android TV 与 Android 手机协同播放的内部演示工程。电视负责播放视频和解码音频，手机通过扫码加入会话，经局域网接收带 PTS 的 PCM 音频，并使用 `AudioTrack` 输出。

## 工程模块

- `tv-app`：电视端应用，负责本地片库、Media3 播放、二维码配对、PCM采集、同步控制和TCP音频发送。
- `phone-app`：手机端应用，负责扫码、TCP音频接收、缓冲、AudioTrack播放、音画对齐和输出设备切换。
- `common`：双端共享的二维码、控制协议、TCP音频帧、PTS和时钟同步等基础代码。

电视端和手机端分别生成独立APK，`common`不能单独安装。

## 当前能力

- 电视端本地视频片库与标准播放控制。
- 手机扫码建立WebSocket控制连接。
- PCM16音频按约20ms分帧，经独立TCP通道发送。
- 基于PTS、单调时钟校准和约定起播完成音画同步。
- 约500ms音频准备后起播，播放中渐进形成约2秒抗抖动储备。
- 支持暂停、继续、跳转、换片和手动音画对齐后的自动重建。
- 最多三部Android手机同时收听，每部手机使用独立控制连接、TCP通道、时钟校准和AudioTrack。
- 手机支持有线耳机、蓝牙耳机和扬声器输出，并可在连接期间切换。
- 单部手机退出、切换输出或发生音频异常时，不影响其他已连接手机。

当前为局域网内部Demo，不包含视频素材，不连接线上视频服务，也未加入传输加密和正式业务鉴权。

## 环境要求

- Android Studio及其自带JBR
- Android SDK 37
- AGP 9.3.1 / Gradle 9.5.0
- Android 7.0（API 24）及以上
- Android电视或盒子一台、Android手机一至三部
- 电视和手机处于同一局域网

首次打开工程时，在Android Studio中选择项目根目录。

## 准备电视端视频

先启动一次电视端应用，再将测试视频复制到：

```text
/sdcard/Android/data/com.example.privatelistening.tv/files/videos/
```

推荐使用MP4视频。同名的JPG、PNG或WebP图片会作为视频海报，例如：

```text
第一集.mp4
第一集.jpg
```

ADB示例：

```powershell
adb -s "电视序列号" push "D:\Videos\第一集.mp4" "/sdcard/Android/data/com.example.privatelistening.tv/files/videos/第一集.mp4"
adb -s "电视序列号" push "D:\Videos\第一集.jpg" "/sdcard/Android/data/com.example.privatelistening.tv/files/videos/第一集.jpg"
```

视频和海报只存放在测试设备中，不打包进APK。

## 演示流程

1. 分别安装并启动 `tv-app` 和 `phone-app`。
2. 电视端从片库选择视频并开始播放。
3. 使用遥控器唤起播放控制栏，选择“同屏自在听”。
4. 手机在应用内点击“扫一扫”，扫描电视二维码。
5. 手机选择扬声器、有线耳机或蓝牙耳机作为输出设备。
6. 电视端开始自在听，双端完成音频准备、缓冲确认、时钟校准和统一起播。
7. 播放过程中可正常暂停、继续、快进、快退、换片或点击“音画对齐”。
8. 手机主动退出后只结束本机收听；最后一部手机退出后结束本次自在听会话。

## 主要代码入口

- 电视播放与会话：`tv-app/src/main/java/com/example/privatelistening/tv/MainActivity.kt`
- 电视PCM采集与TCP发送：`tv-app/src/main/java/com/example/privatelistening/tv/TvAudio.kt`
- 电视配对与控制协议：`tv-app/src/main/java/com/example/privatelistening/tv/pairing/TvPairing.kt`
- 手机前台收听服务：`phone-app/src/main/java/com/example/privatelistening/phone/PhoneListeningService.kt`
- 手机TCP接收与AudioTrack播放：`phone-app/src/main/java/com/example/privatelistening/phone/PhoneAudio.kt`
- 手机配对与控制协议：`phone-app/src/main/java/com/example/privatelistening/phone/PhonePairing.kt`
- TCP音频帧协议：`common/src/main/java/com/example/privatelistening/common/AudioStreamFrame.kt`
- 公共时序和缓冲参数：`common/src/main/java/com/example/privatelistening/common/ListeningTiming.kt`

