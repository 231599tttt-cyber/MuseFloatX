# MuseFloatX Windows 版

桌面版，第 38 版同款剪贴板流程，不是悬浮在 X App 里，是飘在 Windows 桌面上的悬浮球，配合 X 网页用。

## 用法

1. 先在自己电脑上按 TUTORIAL.md 把 muse2api 跑起来，端口 18610，黑窗口不要关；
2. 在 Windows 电脑上装好 Python 3.11+，双击 `启动 Windows 版.bat`，或者在 windows-app 目录执行 `python musefloatx_windows.py`；
3. 主窗口里填：服务地址默认就是 `http://127.0.0.1:18610`（本机，不用填局域网 IP），模型 `muse-spark`，密钥填你 .env 里那串 `m2a_`，点保存、点测试连接；
4. 桌面上会出现一个紫色悬浮球，可以拖动，点一下就出面板；
5. 在浏览器 X 网页里复制一条帖子，再点悬浮球，面板自动读剪贴板生成，风格就是按钮：自然 / 幽默 / 简短，生成中点另一风格是先取消当前那一通再换风格重来，生成中点已选风格直接忽略；
6. 点“复制”后你自己去 X 网页粘贴，不自动发送。

## 特点

- 只用 Python 标准库，不用 pip 装任何包；
- 只做剪贴板流程，不读屏、不读私信、不自动发送、不做账号池、不托管账号；
- 设置保存在 windows-app 目录下的 `musefloatx_windows_settings.json`，只在你自己电脑上，密钥不要发到聊天里；
- 服务关了、黑窗口关了，App 就会连不上，重新双击“启动服务”恢复。

## 打包成 .exe（可选）

本仓库的 GitHub Actions 里有 Windows 构建，推到 main 后会在 Windows Runner 上用 PyInstaller 打包出 `MuseFloatX-Windows.exe`，下载后双击就能跑，不用装 Python。构建产物在 Actions 里，见仓库的 Actions 页。
