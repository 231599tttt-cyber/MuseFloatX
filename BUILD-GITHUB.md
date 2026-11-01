# 不用装 Android Studio，用 GitHub Actions 云端编 APK

这个项目已经带好了云端构建配置（`.github/workflows/build-apk.yml`），
你只需要在浏览器里做几步，不用下载 Android Studio，也不用在电脑上配开发环境。

## 步骤（全程只用浏览器）

1. 打开 GitHub（github.com），登录你的账号，点 New repository，新建一个仓库，
   名字随便起，例如 `MuseFloatX`，选 Public 或者 Private 都可以，点 Create。
2. 在仓库主页点 Add file → Upload files。
3. 把 `MuseFloatX-source.zip` 在电脑上解压，打开解压出来的 `MuseFloatX` 文件夹，
   把里面的全部文件和文件夹（包括 `.github` 这个隐藏文件夹）一起拖到 GitHub 的上传页面。
   注意：不要只上传 zip 本身，要上传解压后的文件，否则 Actions 不会运行。
   如果上传页面看不到 `.github`，先在电脑文件夹里打开“显示隐藏的项目”，
   再拖；或者在 GitHub 里点 Add file → Create new file，路径填
   `.github/workflows/build-apk.yml`，把 workflow 文件内容粘贴进去保存。
4. 点页面下方的 Commit changes 提交。提交后点仓库顶部的 Actions 标签，
   会看到一个正在跑的 Build APK 任务，等它变成绿色对勾（一般几分钟）。
5. 点进那次运行记录，拉到最下面的 Artifacts，下载 `MuseFloatX-debug-apk`，
   解压后里面就是 `app-debug.apk`，传到手机上安装即可。

## 装好之后

- 先在电脑上按 `muse2api-windows-deploy.zip` 里的说明把服务跑起来、导好号。
- 手机和电脑连同一个 WiFi，打开 App，服务地址已经预填为
  `http://10.185.175.157:18610`，模型是 `muse-spark`，只需要填一次密钥，
  点“测试连接”通过后，启动悬浮球，打开 X 点球就能生成回复。

## 如果 Actions 报错

点进失败的那次运行，看红色的那一步，把报错截图发回来，不要自己改配置。
