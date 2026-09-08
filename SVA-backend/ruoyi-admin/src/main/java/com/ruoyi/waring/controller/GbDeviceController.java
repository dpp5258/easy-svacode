package com.ruoyi.waring.controller;

import java.util.List;
import java.util.Map;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.service.HDeviceService;
import com.ruoyi.waring.service.impl.GbSimulatorService;
import com.ruoyi.waring.service.impl.WvpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GB28181 国标设备"登记 + 模拟器"（easySVA 侧便捷入口）。
 * 说明：真实国标 IPC 仍需在设备侧配置注册；本接口用于测试/演示与预登记。
 */
@RestController
@RequestMapping("/waring/device/gb")
public class GbDeviceController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(GbDeviceController.class);

    @Autowired
    private WvpClient wvpClient;

    @Autowired
    private HDeviceService hDeviceService;

    @Autowired
    private GbSimulatorService gbSimulatorService;

    /**
     * 登记国标设备到 WVP（POST body: deviceId/name/password），随后同步一次到本地 h_device。
     */
    @PreAuthorize("@ss.hasPermi('waring:device:add')")
    @PostMapping("/register")
    public AjaxResult register(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        if (deviceId == null || !deviceId.matches("\\d{20}")) {
            return error("设备国标编码必须为 20 位数字");
        }
        try {
            wvpClient.deviceAdd(deviceId, str(body.get("name")), str(body.get("password")));
        } catch (Exception e) {
            return error("登记到 WVP 失败: " + e.getMessage());
        }
        try {
            hDeviceService.syncGbDevicesOnce();
        } catch (Exception e) {
            log.warn("登记后同步失败: {}", e.getMessage());
        }
        return success("已登记到 WVP 并同步（设备 REGISTER 上线后自动置在线）");
    }

    /**
     * 一键启动本机模拟国标设备（POST body: deviceId/channelId/name/password/source）。
     * source: test(彩条) 或 file:///tmp/sleep_gb.mp4 等。
     */
    @PreAuthorize("@ss.hasPermi('waring:device:add')")
    @PostMapping("/sim/start")
    public AjaxResult simStart(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        if (deviceId == null || !deviceId.matches("\\d{20}")) {
            return error("设备国标编码必须为 20 位数字");
        }
        // 注意：不预 deviceAdd——让模拟器 REGISTER 时由 WVP 自动建档（transport/心跳等字段才完整，
        //      与 Superdock 同路径；预建记录会缺字段导致 WVP 点播/状态查询报 null arg）。
        try {
            return success(gbSimulatorService.start(body));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    /** 停止本机模拟设备（POST body: deviceId）。 */
    @PreAuthorize("@ss.hasPermi('waring:device:remove')")
    @PostMapping("/sim/stop")
    public AjaxResult simStop(@RequestBody Map<String, Object> body) {
        String deviceId = str(body.get("deviceId"));
        int killed = gbSimulatorService.stop(deviceId);
        return success("已停止模拟设备 " + killed + " 个");
    }

    /** 本机正在运行的模拟设备列表。 */
    @PreAuthorize("@ss.hasPermi('waring:device:query')")
    @GetMapping("/sim/list")
    public AjaxResult simList() {
        List<Map<String, String>> list = gbSimulatorService.list();
        return success(list);
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
