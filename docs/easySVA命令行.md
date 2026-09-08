部署产物齐全。下面是**三个源码服务的完整运维命令手册**(以 rc.local 部署目录 `/opt/SVA/{backend,mediaServer,server}` 为准,与你当前手动实例等效):

---

# 🖥️ 一、后端服务(Java / RuoYi)—— 端口 9114

```bash
# 【状态】
ps -ef | grep '[j]ava -jar backend.jar'          # 进程
ss -tln | grep 9114                              # 端口

# 【启动】(前台阻塞 / 后台运行)
cd /opt/SVA/backend && sudo java -jar backend.jar          # 前台(看日志直接输出)
cd /opt/SVA/backend && sudo nohup java -jar backend.jar > log.out 2>&1 &   # 后台

# 【停止】(注意 ^ 锚定,防误杀)
sudo pkill -f '^java -jar backend.jar'; sleep 3; pgrep -af '[j]ava -jar backend'

# 【重启】= 停 + 启
sudo pkill -f '^java -jar backend.jar'; sleep 3
cd /opt/SVA/backend && sudo nohup java -jar backend.jar > log.out 2>&1 &

# 【编译(改完代码后)】
cd /opt/SVA/SVA-backend
sudo mvn clean package -DskipTests               # 产物: ruoyi-admin/target/ruoyi-admin.jar
sudo cp ruoyi-admin/target/ruoyi-admin.jar /opt/SVA/backend/backend.jar   # 同步到部署目录

# 【日志】
tail -f /opt/SVA/backend/log.out                 # 实时
tail -100 /opt/SVA/backend/log.out               # 最近100行
grep -iE "error|exception" /opt/SVA/backend/log.out | tail -20   # 查错
```

---

# 🎥 二、MediaServer 流媒体(ZLMediaKit)—— 9992/9994/9995

```bash
# 【状态】
ps -ef | grep '[M]ediaServer'
ss -tln | grep -E ':9992|:9994|:9995'

# 【启动】
cd /opt/SVA/mediaServer && sudo nohup ./MediaServer > log.out 2>&1 &
# (开发时也可用源码编译产物)
cd /opt/SVA/SVA-mediaServer/release/linux/Release && sudo nohup ./MediaServer > log.out 2>&1 &

# 【停止】(有两个进程:主+守护,一次全杀)
sudo pkill -f 'MediaServer -d'; sleep 2; pgrep -af '[M]ediaServer'

# 【重启】
sudo pkill -f 'MediaServer -d'; sleep 2
cd /opt/SVA/mediaServer && sudo nohup ./MediaServer > log.out 2>&1 &

# 【编译(改完代码后)】
cd /opt/SVA/SVA-mediaServer && sudo mkdir -p build && cd build
sudo cmake -D CMAKE_BUILD_TYPE=Release -D ENABLE_WEBRTC=OFF -D ENABLE_SRT=OFF -D ENABLE_TESTS=OFF -D ENABLE_MEM_DEBUG=OFF ..
sudo make -j$(nproc)
sudo cp release/linux/Release/MediaServer /opt/SVA/mediaServer/   # 同步部署
# (若改了 conf/config.ini 也要 cp 过去)

# 【日志】(彩色,去掉转义码看更清爽)
tail -f /opt/SVA/mediaServer/log.out
tail -f /opt/SVA/mediaServer/log.out | sed 's/\x1b\[[0-9;]*m//g'
```

---

# 🤖 三、AI 分析器 Analyzer(C++)—— 端口 9993

```bash
# 【状态】
ps -ef | grep '[A]nalyzer -f'
ss -tln | grep 9993

# 【启动】(必须带 -f 配置文件)
cd /opt/SVA/server && sudo nohup ./Analyzer -f /opt/SVA/config.json > log.out 2>&1 &

# 【停止】
sudo pkill -f '^\./Analyzer'; sleep 2; pgrep -af '[A]nalyzer -f'

# 【重启】
sudo pkill -f '^\./Analyzer'; sleep 2
cd /opt/SVA/server && sudo nohup ./Analyzer -f /opt/SVA/config.json > log.out 2>&1 &

# 【编译(改完代码后)】
cd /opt/SVA/SVA-server/build
sudo make -j$(nproc)                              # 增量编译
sudo cp /opt/SVA/SVA-server/build/Analyzer /opt/SVA/server/Analyzer   # 同步部署
# 说明:CMake 已配置过;若从零编: sudo cmake .. -DSVA_ONNXRUNTIME_GPU=OFF

# 【日志】
tail -f /opt/SVA/server/log.out                   # 实时(推理告警都在这里)
grep -iE "error|fail|告警" /opt/SVA/server/log.out | tail -20
```

---

# 🏗️ 四、基础设施(非源码,但系统依赖)

```bash
# Nginx(前端入口,端口 80)
sudo systemctl status nginx     # 状态
sudo systemctl restart nginx    # 重启
sudo nginx -t                   # 配置检查
sudo tail -f /var/log/nginx/access.log /var/log/nginx/error.log

# MariaDB(端口 3306)
sudo systemctl status mariadb;  sudo systemctl restart mariadb
mysql -uroot -peasySVA.EZ easySVA

# Redis(端口 6379)
sudo systemctl status redis-server;  sudo systemctl restart redis-server
redis-cli ping    # 应返回 PONG
```

---

# ⚡ 五、一键启停 / 开机自启

```bash
# 一键停止全部三个服务
sudo pkill -f '^java -jar backend.jar'; sudo pkill -f 'MediaServer -d'; sudo pkill -f '^\./Analyzer'

# 一键启动全部(等价 rc.local 内容)
sudo bash -c '
sleep 3; cd /opt/SVA/backend && nohup java -jar backend.jar > log.out 2>&1 &
sleep 2; cd /opt/SVA/mediaServer && nohup ./MediaServer > log.out 2>&1 &
sleep 2; cd /opt/SVA/server && nohup ./Analyzer -f /opt/SVA/config.json > log.out 2>&1 &'

# 开机自启:启用 rc.local(跨重启生效)
sudo systemctl enable rc-local.service
# 立即按 rc.local 拉起(等同重启后效果)
sudo systemctl start rc-local.service
# 关闭自启
sudo systemctl disable rc-local.service
# 查看 rc.local 内容
cat /etc/rc.local
```

---

## ⚠️ 三个易错提醒

1. **`pkill -f` 模式会匹配命令行文本**——用 `'^java -jar backend.jar'`、`'^\./Analyzer'` 这类锚定写法,否则可能误杀你自己的 shell(我之前就踩过)
2. **所有编译/启动都用 `sudo`**:源码与部署目录都是 root 属主;`make`/`mvn` 不带 sudo 会权限报错
3. **改源码后 = 编译 → cp 到部署目录 → 重启**,三步缺一不可(rc.local 用的都是部署目录里的产物)

---

要不要我把上面整理成一个**带子命令的运维脚本**放到 `/opt/SVA/easySVA-ops.sh`?用法类似 `sudo ./easySVA-ops.sh backend start|stop|restart|log|status`,以后不用记这么多命令。
---

# 🔌 附：本地视频当"测试流"推流 + CPU 看门狗（2026-09-04 补充）

## 一、本地视频循环推流（把 mp4 变成一路 rtsp, 供布控/页面当摄像头用）

```bash
# 【推流】用法: push_loop.sh <mp4> [app] [stream] [码率]  (默认 app=live stream=cam439081)
/opt/SVA/push_loop.sh "/home/male/临时easySVA/video/xxx.mp4" live cam439081 3M

# 【大分辨率/竖屏视频建议加缩放 + 单线程, 减轻 CPU】(等价手动 ffmpeg)
ffmpeg -hide_banner -loglevel error -threads 1 -stream_loop -1 -re -i 视频.mp4 \
  -an -threads 1 -vf "scale=720:1280" -c:v libx264 -preset fast -tune zerolatency \
  -pix_fmt yuv420p -g 25 -b:v 2M -f flv rtmp://127.0.0.1:9995/live/cam439081

# 【验证流已可拉】
ffprobe -v error -rtsp_transport tcp -show_entries stream=width,height rtsp://127.0.0.1:9994/live/cam439081

# 【停止推流】按 PID kill(或 pgrep -f '^ffmpeg' 后 kill); 注意别用未锚定 pkill(见易错提醒1)
```

> 注：推流层 = 页面"启动监控"之外的更底层造流；平台设备/布控才消费它。只推流不会自动告警, 需启动布控。

## 二、CPU 看门狗（保护机器: CPU 异常自动关停 SVA）

```bash
# 【启动】(后台常驻, 日志 /tmp/sva_cpu_watchdog.log)
nohup bash /tmp/sva_cpu_watchdog.sh > /dev/null 2>&1 &

# 【只观测不关停】(调试用)
SVA_WATCH_DRYRUN=1 bash /tmp/sva_cpu_watchdog.sh

# 【停止】
pgrep -f sva_cpu_watchdog | xargs -r kill
```

- 采样: 每 5s 按 `/proc/<pid>/stat` 算真实 CPU(非 top 均值)
- 档A 空转保护: Analyzer 在跑且**布控数为 0** 但 CPU≥40%(单核) 持续 20s → 自动关停(防死源/空转烧核)
- 档B 失控保护: 任一 SVA 进程 CPU≥900%(≈9核) 持续 30s → 自动关停(多算法并发正常推理远低于此)
- 关停动作: 先停布控(DB 一致)→ 杀 backend/MediaServer/Analyzer/ffmpeg
- 阈值可用环境变量调: `SVA_A_IDLE` / `SVA_B_HEAVY` / `SVA_INTERVAL`

## 三、CPU 占用治理要点（2026-09-04 实测）

- Analyzer 推理吃核是**正常工作量**(非死源空转): ORT 默认每会话吃满全核, 多布控并发会过载
- 已内置限线程: `SetIntraOpNumThreads(4)/SetInterOpNumThreads(1)`(Analyzer 构造器)
- 实测(20 线程 i7-13650HX, 纯 CPU): 双布控并发≈7核; 单 pose 睡岗路≈0.4核; ffmpeg(-threads 1)≈1核
- 想更快: 降 detectFps / 用轻量模型 / 单 pose 模型代替 yolo 路
