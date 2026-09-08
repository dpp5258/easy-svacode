package com.ruoyi.waring.service;

import com.ruoyi.waring.domain.HDevice;

import java.util.List;
import java.util.Map;

public interface HDeviceService {
    void insertDevice(HDevice device);

    void deleteDevice();

    HDevice selectDeviceByApeId(String apeId);

    int insertDeviceCrud(HDevice device);

    int updateDevice(HDevice device);

    /**
     * 删除设备(可多台)。国标设备(GB28181)执行"彻底删除":
     * 停点播 → 停同编号本机模拟器 → 从归属 WVP 平台删除 → 删本地行(避免 10s 同步复活);
     * RTSP/直连设备仅删本地行。返回给前端展示的汇总文案; 全部失败抛 ServiceException。
     */
    String deleteDeviceByApeIds(String[] apeIds);

    List<HDevice> selectDeviceList(HDevice device, Long userId);

    Map<String, Object> getDeviceNum(Long userId);

    Map<String, Object> getDirectLiveUrl(String apeId);

    List<HDevice> selectLDeviceList(HDevice device, Long userId);

    int startMonitor(String apeId);

    int stopMonitor(String apeId);

    Map<String, Object> previewMonitor(String apeId);

    /**
     * 云台控制(仅 GB28181 设备): easySVA 适配 → WVP PTZ SIP 信令 → IPC。
     * command: left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop
     */
    String ptzControl(String apeId, String command, Integer horizonSpeed, Integer verticalSpeed,
        Integer zoomSpeed);

    /** 云台归位(回中)。 */
    String ptzHome(String apeId);

    /** 调用预置位。 */
    String ptzPresetCall(String apeId, int presetId);

    /** 设置预置位。 */
    String ptzPresetAdd(String apeId, int presetId);

    /** 手动触发一次"从 WVP 同步国标设备"（前端"从WVP同步"按钮；与 @Scheduled 轮询共用实现） */
    void syncGbDevicesOnce();
}
