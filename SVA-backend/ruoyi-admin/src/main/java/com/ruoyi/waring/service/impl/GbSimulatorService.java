package com.ruoyi.waring.service.impl;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.WvpServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.WvpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * GB28181 模拟设备(sbgb28181)托管：本机一键启停，用于演示/联调。
 * 说明：仅支持本机跑模拟器（easySVA 与 WVP 同机或同网段），真实 IPC 走设备侧配置。
 * 平台选择优先级：body.wvp_server_id > 该设备已绑定的 h_device.wvp_server_id > 默认平台 id=1。
 */
@Service
public class GbSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(GbSimulatorService.class);

    /** 模拟器所在目录（队友/本机已部署 sb-im/sbgb28181）。 */
    private static final String SIM_DIR = "/opt/wvp/sim";
    private static final String SIM_SCRIPT = SIM_DIR + "/gb28181_pusher.py";
    private static final String GST_PLUGIN_PATH = SIM_DIR + "/gst-gb28181sink/build";
    /** WVP 平台国标编码（= WVP application.yml sip.id，团队统一）。 */
    private static final String PLATFORM_SIP_ID = "34020000002000000001";

    @Autowired
    private WvpServerMapper wvpServerMapper;

    @Autowired
    private HDeviceMapper hDeviceMapper;

    /** 解析本次模拟器要注册到的平台（见类注释优先级）；无可用平台回落 null。 */
    private WvpServer resolveServer(Map<String, Object> p, String deviceId) {
        Long sid = null;
        Object o = p.get("wvp_server_id");
        if (o instanceof Number) {
            sid = ((Number) o).longValue();
        } else if (o != null) {
            try {
                sid = Long.valueOf(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (sid == null && deviceId != null && !deviceId.isEmpty()) {
            HDevice d = hDeviceMapper.selectByGbId(deviceId);
            if (d != null) {
                sid = d.getWvp_server_id();
            }
        }
        WvpServer s = wvpServerMapper.selectEnabledById(sid == null ? 1L : sid);
        if (s == null || StringUtils.isBlank(s.getHost())) {
            s = wvpServerMapper.selectEnabledById(1L);
        }
        return s;
    }

    /** 启动一个模拟设备（先停同编号旧实例）。返回 pid 与日志路径。 */
    public Map<String, Object> start(Map<String, Object> p) {
        String deviceId = str(p.get("deviceId"));
        String channelId = str(p.get("channelId"));
        if (deviceId == null || !deviceId.matches("\\d{20}")) {
            throw new ServiceException("设备国标编码必须为 20 位数字");
        }
        if (channelId == null || !channelId.matches("\\d{20}")) {
            channelId = deviceId.substring(0, 10) + "23" + deviceId.substring(12); // 缺省推导通道号
        }
        String password = str(p.get("password"));
        if (password == null || password.isEmpty()) {
            password = "12345678";
        }
        String source = str(p.get("source"));
        if (source == null || source.isEmpty()) {
            source = "test";
        }
        stop(deviceId);

        WvpServer srv = resolveServer(p, deviceId);
        String host = (srv != null && StringUtils.isNotBlank(srv.getHost())) ? srv.getHost().trim() : "127.0.0.1";
        long platformId = (srv != null) ? srv.getId() : 1L;
        List<String> cmd = new ArrayList<>();
        cmd.add("python3");
        cmd.add(SIM_SCRIPT);
        cmd.add("--server-ip");
        cmd.add(host);
        cmd.add("--server-port");
        cmd.add("5060");
        cmd.add("--server-id");
        cmd.add(PLATFORM_SIP_ID);
        cmd.add("--agent-id");
        cmd.add(deviceId);
        cmd.add("--agent-password");
        cmd.add(password);
        cmd.add("--channel-id");
        cmd.add(channelId);
        cmd.add("--source");
        cmd.add(source);
        cmd.add("--udp");
        try {
            File logFile = new File(SIM_DIR, "auto_" + deviceId + ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(SIM_DIR));
            Map<String, String> env = pb.environment();
            env.put("GST_PLUGIN_PATH", GST_PLUGIN_PATH);
            pb.redirectOutput(logFile);
            pb.redirectError(logFile);
            Process proc = pb.start();
            Thread.sleep(1000);
            if (!proc.isAlive()) {
                throw new ServiceException("模拟器启动即退出，请查看 " + logFile.getAbsolutePath());
            }
            Map<String, Object> r = new HashMap<>();
            r.put("pid", proc.pid());
            r.put("log", logFile.getAbsolutePath());
            r.put("deviceId", deviceId);
            r.put("channelId", channelId);
            r.put("platformId", platformId);
            r.put("sip", host + ":5060");
            r.put("msg", "模拟设备已启动（等待 WVP 注册）");
            return r;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("启动模拟器失败: " + e.getMessage());
        }
    }

    /** 停止指定编号的模拟器（按进程命令行匹配 agent-id）。 */
    public int stop(String deviceId) {
        int killed = 0;
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            String cmdline = readCmdline(ph);
            if (cmdline != null && cmdline.contains("gb28181_pusher.py") && cmdline.contains("--agent-id")
                && (deviceId == null || cmdline.contains(deviceId))) {
                ph.destroy();
                killed++;
            }
        }
        return killed;
    }

    /** 列出当前运行的模拟器。 */
    public List<Map<String, String>> list() {
        List<Map<String, String>> out = new ArrayList<>();
        for (ProcessHandle ph : ProcessHandle.allProcesses().toList()) {
            String cmdline = readCmdline(ph);
            if (cmdline == null || !cmdline.contains("gb28181_pusher.py")) {
                continue;
            }
            Map<String, String> m = new HashMap<>();
            m.put("pid", String.valueOf(ph.pid()));
            m.put("deviceId", extractArg(cmdline, "--agent-id"));
            m.put("channelId", extractArg(cmdline, "--channel-id"));
            out.add(m);
        }
        return out;
    }

    private String readCmdline(ProcessHandle ph) {
        try {
            byte[] b = Files.readAllBytes(ph.info().command().isEmpty()
                ? null : new File("/proc/" + ph.pid() + "/cmdline").toPath());
            return new String(b, StandardCharsets.UTF_8).replace('\0', ' ');
        } catch (Exception e) {
            return null;
        }
    }

    private String extractArg(String cmdline, String key) {
        String[] parts = cmdline.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(key)) {
                return parts[i + 1];
            }
        }
        return "";
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
