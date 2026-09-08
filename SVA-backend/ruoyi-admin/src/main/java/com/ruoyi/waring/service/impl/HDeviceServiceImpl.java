package com.ruoyi.waring.service.impl;


import com.github.pagehelper.PageHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.domain.entity.SysDept;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.page.PageDomain;
import com.ruoyi.common.core.page.TableSupport;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.system.mapper.SysDeptMapper;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.waring.domain.HDevice;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import com.ruoyi.waring.service.HDeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;


@Service
@Component
public class HDeviceServiceImpl implements HDeviceService {

    private static final Logger log = LoggerFactory.getLogger(HDeviceServiceImpl.class);

    private static final String STREAM_SOURCE_TYPE_DIRECT = "DIRECT";
    private static final String STREAM_SOURCE_TYPE_PLATFORM = "PLATFORM";
    private static final String DEVICE_TYPE_GB28181 = "GB28181";
    private static final String DEVICE_TYPE_RTSP = "RTSP";
    private static final String GB_STATUS_ONLINE = "ONLINE";
    private static final String GB_STATUS_OFFLINE = "OFFLINE";
    private static final int MAX_APE_ID_GENERATE_RETRY = 20;
    private static final Pattern STREAM_NAME_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String MONITOR_STATUS_RUNNING = "RUNNING";
    private static final String MONITOR_STATUS_STOPPED = "STOPPED";
    private static final String MONITOR_STATUS_STARTING = "STARTING";
    private static final String MONITOR_STATUS_STOPPING = "STOPPING";
    private static final String MONITOR_STATUS_ERROR = "ERROR";
    private static final long DEFAULT_SERVER_ID = 1L;
    private static final String DEFAULT_ZLM_APP = "live";

    @Autowired
    HDeviceMapper hDeviceMapper;

    @Autowired
    SysUserMapper userMapper;

    @Autowired
    SysDeptMapper sysDeptMapper;

    @Autowired
    ZlmServerMapper zlmServerMapper;

    @Autowired(required = false)
    private RestTemplate restTemplate;

    @Autowired
    private WvpClient wvpClient;

    @Autowired
    private GbSimulatorService gbSimulatorService;

    @PostConstruct
    private void initRestTemplate() {
        if (restTemplate == null) {
            restTemplate = new RestTemplate();
        }
    }

    @Override
    public void insertDevice(HDevice device) {
        hDeviceMapper.insertDevice(device);
    }

    @Override
    public void deleteDevice() {
        hDeviceMapper.deleteDevice();
    }

    @Override
    public HDevice selectDeviceByApeId(String apeId) {
        return hDeviceMapper.selectDeviceByApeId(apeId);
    }

    @Override
    public int insertDeviceCrud(HDevice device) {
        normalizeStreamSourceType(device, null);
        validateStreamSourceRule(device, null);
        if (StringUtils.isBlank(device.getOrg_name())) {
            throw new ServiceException("组织名称不能为空");
        }
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));

        if (StringUtils.isBlank(device.getApe_id())) {
            device.setApe_id(generateUniqueApeId());
        } else if (hDeviceMapper.selectDeviceByApeId(device.getApe_id()) != null) {
            throw new ServiceException("设备编码已存在: " + device.getApe_id());
        }

        return hDeviceMapper.insertDeviceCrud(device);
    }

    @Override
    public int updateDevice(HDevice device) {
        if (StringUtils.isBlank(device.getApe_id())) {
            throw new ServiceException("设备编码不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(device.getApe_id());
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + device.getApe_id());
        }

        // 国标设备改名: 名称源头在 WVP, 必须先同步到归属 WVP(在线也可改, 已实测);
        // 失败则抛异常、本地不更新, 避免改名后又被 10s 同步按 WVP 旧名回滚。
        if (isGbDevice(existedDevice) && StringUtils.isNotBlank(existedDevice.getGb_id())
            && StringUtils.isNotBlank(device.getName())
            && !StringUtils.equals(device.getName(), existedDevice.getName())) {
            wvpClient.updateDeviceName(existedDevice.getWvp_server_id(), existedDevice.getGb_id(),
                device.getName());
        }

        normalizeStreamSourceType(device, existedDevice);
        validateStreamSourceRule(device, existedDevice);
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));

        return hDeviceMapper.updateDevice(device);
    }

    private void normalizeStreamSourceType(HDevice device, HDevice existedDevice) {
        String streamSourceType = device.getStream_source_type();
        if (StringUtils.isBlank(streamSourceType) && existedDevice != null) {
            streamSourceType = existedDevice.getStream_source_type();
        }
        if (StringUtils.isBlank(streamSourceType)) {
            streamSourceType = STREAM_SOURCE_TYPE_DIRECT;
        }

        streamSourceType = StringUtils.upperCase(streamSourceType.trim());
        if (!STREAM_SOURCE_TYPE_DIRECT.equals(streamSourceType) && !STREAM_SOURCE_TYPE_PLATFORM.equals(streamSourceType)) {
            throw new ServiceException("stream_source_type 仅支持 PLATFORM 或 DIRECT");
        }
        device.setStream_source_type(streamSourceType);
    }

    private void validateStreamSourceRule(HDevice device, HDevice existedDevice) {
        if (!STREAM_SOURCE_TYPE_DIRECT.equals(device.getStream_source_type())) {
            return;
        }

        String finalName = pickFinalValue(device.getName(), existedDevice == null ? null : existedDevice.getName());
        if (StringUtils.isBlank(finalName)) {
            throw new ServiceException("DIRECT 设备类型下，name 不能为空");
        }

        String finalDirectSourceUrl = pickFinalValue(device.getDirect_source_url(), existedDevice == null ? null : existedDevice.getDirect_source_url());
        if (StringUtils.isBlank(finalDirectSourceUrl)) {
            throw new ServiceException("DIRECT 设备类型下，direct_source_url 不能为空");
        }
    }

    private String pickFinalValue(String incomingValue, String existedValue) {
        if (incomingValue != null) {
            return incomingValue;
        }
        return existedValue;
    }

    private String generateUniqueApeId() {
        for (int i = 0; i < MAX_APE_ID_GENERATE_RETRY; i++) {
            String candidate = "cam" + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            if (hDeviceMapper.selectDeviceByApeId(candidate) == null) {
                return candidate;
            }
        }
        throw new ServiceException("自动生成设备编码失败，请稍后重试");
    }

    @Override
    public String deleteDeviceByApeIds(String[] apeIds) {
        if (apeIds == null || apeIds.length == 0) {
            throw new ServiceException("请选择要删除的设备");
        }
        List<String> ok = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        for (String apeId : apeIds) {
            try {
                deleteOneDeviceCompletely(apeId);
                ok.add(apeId);
            } catch (Exception e) {
                log.error("删除设备失败 apeId={} err={}", apeId, e.getMessage(), e);
                fail.add(apeId + ": " + e.getMessage());
            }
        }
        if (ok.isEmpty() && !fail.isEmpty()) {
            throw new ServiceException("删除失败: " + fail.get(0));
        }
        String msg = "已删除 " + ok.size() + " 台设备";
        if (!fail.isEmpty()) {
            msg += "；以下 " + fail.size() + " 台未删除: " + String.join("；", fail);
        }
        return msg;
    }

    /**
     * 单台彻底删除：
     * - GB28181 国标设备: ①停正在点播的 WVP 流 ②停同编号本机模拟器 ③从归属 WVP 平台删除设备
     *   (失败则抛异常、本地行保留, 避免删除后又被 10s 同步自动复活) ④删本地行。
     * - RTSP/直连设备: 仅删本地行(原行为不变)。
     */
    private void deleteOneDeviceCompletely(String apeId) {
        HDevice dev = hDeviceMapper.selectDeviceByApeId(apeId);
        if (dev == null) {
            return; // 本就不存在, 视为已删除
        }
        if (isGbDevice(dev) && StringUtils.isNotBlank(dev.getGb_id())) {
            // ① 若有点播中的流先停(避免孤儿流); 失败不阻断删除
            if (StringUtils.isNotBlank(dev.getGb_channel_id())) {
                try {
                    wvpClient.playStop(dev.getWvp_server_id(), dev.getGb_id(), dev.getGb_channel_id());
                } catch (Exception e) {
                    log.warn("删除前停 WVP 点播失败(继续删除) apeId={} err={}", apeId, e.getMessage());
                }
            }
            // ② 停同编号本机模拟器(尽力, 失败不阻断)
            try {
                gbSimulatorService.stop(dev.getGb_id());
            } catch (Exception e) {
                log.warn("停止模拟器失败(继续删除) deviceId={} err={}", dev.getGb_id(), e.getMessage());
            }
            // ③ 从归属 WVP 平台删除(按设备归属平台路由; 失败抛异常 → 本地行保留)
            wvpClient.deleteDevice(dev.getWvp_server_id(), dev.getGb_id());
        }
        // ④ 删本地行
        hDeviceMapper.deleteDeviceByApeIds(new String[] { apeId });
    }

    @Override
    public Map<String, Object> getDirectLiveUrl(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        if (!STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())) {
            throw new ServiceException("仅支持 DIRECT 设备类型");
        }

        if (StringUtils.isBlank(device.getDirect_source_url())) {
            throw new ServiceException("DIRECT 设备类型下，direct_source_url 不能为空");
        }

        Long zlmServerId = device.getZlm_server_id() == null ? DEFAULT_SERVER_ID : device.getZlm_server_id();
        ZlmServer zlmServer = zlmServerMapper.selectEnabledById(zlmServerId);
        if (zlmServer == null) {
            throw new ServiceException("设备未绑定可用ZLM服务器");
        }
        if (StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null || zlmServer.getMedia_http_port() == null) {
            throw new ServiceException("可用ZLM服务器配置缺失");
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();

        String stream = sanitizeStreamName(apeId);
        String addProxyUrl = UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/addStreamProxy")
                .queryParam("vhost", "__defaultVhost__")
                .queryParam("app", zlmApp)
                .queryParam("stream", stream)
                .queryParam("url", device.getDirect_source_url())
            .queryParam("enable_mp4", 1)
            .queryParam("auto_close", 0)
                .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                        ? java.util.Optional.of(zlmServer.getSecret())
                        : java.util.Optional.empty())
                .build(true)
                .toUriString();

            if (log.isDebugEnabled()) {
                log.debug("调用ZLM addStreamProxy, apeId={}, url={}", apeId, maskSensitiveUrl(addProxyUrl));
            }

        ResponseEntity<String> response = restTemplate.getForEntity(addProxyUrl, String.class);
        String body = response.getBody();
        if (StringUtils.isBlank(body)) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: empty response");
        }

        int code;
        String msg;
        String zlmProxyKey;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            code = parseCode(root.path("code").asText());
            msg = root.path("msg").asText("");
            zlmProxyKey = root.path("data").path("key").asText("");
        } catch (Exception e) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: 响应解析异常");
        }

        boolean addProxySuccess = code == 0;
        boolean addProxyAlreadyExists = code != 0 && isAddProxyAlreadyExists(msg);

        if (!addProxySuccess && !addProxyAlreadyExists) {
            throw new ServiceException("调用 ZLM addStreamProxy 失败: " + msg);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("apeId", apeId);
        result.put("stream", stream);
        result.put("playUrl", "ws://" + zlmServer.getHost() + ":" + zlmServer.getMedia_http_port() + "/" + zlmApp + "/" + stream + ".live.flv");
        result.put("zlmProxyKey", StringUtils.isBlank(zlmProxyKey) ? null : zlmProxyKey);
        result.put("addProxySuccess", addProxySuccess);
        result.put("addProxyAlreadyExists", addProxyAlreadyExists);
        result.put("protocol", "ws-flv");
        return result;
    }

    @Override
    public List<HDevice> selectDeviceList(HDevice device, Long userId) {
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));
        List<HDevice> devices;
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        List<String> orgIndexs = null;
        if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
            // 如果登录账号不为admin 账号
            if (device.getOrg_index() == null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        } else if (!dept.getOrgIndex().equals("10")) {
            // 如果登录账号不为 hy 账号
            if (device.getOrg_index() == null) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            }
        } else {
            // 如果登录账号为 hy/admin 账号
            if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            } else {
                if (!dept.getOrgIndex().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                } else if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        }

        PageDomain pageDomain = TableSupport.getPageDomain();
        PageHelper.startPage(pageDomain.getPageNum(), pageDomain.getPageSize(), pageDomain.getOrderBy());
        devices = hDeviceMapper.selectDeviceList(device);

        return devices;
    }

    @Override
    public Map<String, Object> getDeviceNum(Long userId) {
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        int deviceNum;
        int deviceEnableNum;
        if (com.ruoyi.common.utils.SecurityUtils.isAdmin(userId) || dept.getOrgIndex().equals("10")) {
            // 如果登录账号为 集团管理员和系统管理员 查询所有数量的设备
            deviceNum = hDeviceMapper.getDeviceNum();
            deviceEnableNum = hDeviceMapper.getDeviceEnableNum();
        } else {
            // 如果登录账号为 别的账号 根据大组织查询
            List<String> orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
            orgIndexs.add(dept.getOrgIndex());
            String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
            HDevice device = new HDevice();
            device.getParams().put("org_indexs", org_index);
            deviceNum = hDeviceMapper.getDeviceNumByOrg(device);
            deviceEnableNum = hDeviceMapper.getDeviceEnableNumByOrg(device);
        }
        int deviceli = deviceNum - deviceEnableNum;
        Map<String, Object> map = new HashMap<>();
        map.put("deviceNum", deviceNum);
        map.put("deviceEnableNum", deviceEnableNum);
        map.put("deviceli", deviceli);
        return map;
    }

    @Override
    public List<HDevice> selectLDeviceList(HDevice device, Long userId) {
        device.setOrg_index(normalizeOrgIndex(device.getOrg_index()));
        List<HDevice> devices;
        SysUser user = userMapper.selectUserById(userId);
        SysDept dept = sysDeptMapper.selectDeptById(user.getDeptId());
        List<String> orgIndexs = null;
        if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
            // 如果登录账号不为admin 账号
            if (device.getOrg_index() == null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null && !dept.getOrgIndex().equals("10")) {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        } else if (!dept.getOrgIndex().equals("10")) {
            // 如果登录账号不为 hy 账号
            if (device.getOrg_index() == null) {
                orgIndexs = sysDeptMapper.getOrgIndex(dept.getOrgIndex());
                orgIndexs.add(dept.getOrgIndex());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            } else {
                orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                orgIndexs.add(device.getOrg_index());
                String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                device.getParams().put("org_indexs", org_index);
            }
        } else {
            // 如果登录账号为 hy/admin 账号
            if (device.getOrg_index() != null) {
                if (!device.getOrg_index().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            } else {
                if (!dept.getOrgIndex().equals("10")) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                } else if (!com.ruoyi.common.utils.SecurityUtils.isAdmin(userId)) {
                    orgIndexs = sysDeptMapper.getOrgIndex(device.getOrg_index());
                    orgIndexs.add(device.getOrg_index());
                    String[] org_index = orgIndexs.toArray(new String[orgIndexs.size()]);
                    device.getParams().put("org_indexs", org_index);
                }
            }
        }

        PageDomain pageDomain = TableSupport.getPageDomain();
        PageHelper.startPage(pageDomain.getPageNum(), pageDomain.getPageSize(), pageDomain.getOrderBy());
        devices = hDeviceMapper.selectLDeviceList(device);

        return devices;
    }

    @Override
    public int startMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(apeId);
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        if (isGbDevice(existedDevice)) {
            Map<String, Object> gb = buildGb28181Play(existedDevice);
            Object gbPlayUrl = gb.get("playUrl");
            if (gbPlayUrl != null) {
                hDeviceMapper.updatePlayUrlByApeId(apeId, String.valueOf(gbPlayUrl));
            }
            int gbUpdated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_RUNNING);
            if (gbUpdated <= 0) {
                throw new ServiceException("启动监控失败: " + apeId);
            }
            return gbUpdated;
        }

        String startAddProxyUrl = buildDirectAddProxyUrl(existedDevice);
        String startPlayUrl = buildDirectPlayUrl(existedDevice);
        if (isDirectDevice(existedDevice)) {
            Map<String, Object> directLiveInfo = getDirectLiveUrl(apeId);
            boolean addProxyAlreadyExists = Boolean.TRUE.equals(directLiveInfo.get("addProxyAlreadyExists"));
            if (addProxyAlreadyExists) {
                throw new ServiceException("设备监控已经启动过");
            }

            Object playUrlObj = directLiveInfo.get("playUrl");
            Object zlmProxyKeyObj = directLiveInfo.get("zlmProxyKey");
            if (playUrlObj != null) {
                startPlayUrl = String.valueOf(playUrlObj);
            }
            String zlmProxyKey = zlmProxyKeyObj == null ? null : String.valueOf(zlmProxyKeyObj);
            hDeviceMapper.updatePlayUrlByApeId(apeId, startPlayUrl);
            if (StringUtils.isNotBlank(zlmProxyKey)) {
                hDeviceMapper.updateZlmProxyKeyByApeId(apeId, zlmProxyKey);
            }
        }

        int updated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_RUNNING);
        if (updated <= 0) {
            throw new ServiceException("启动监控失败: " + apeId);
        }
        return updated;
    }

    @Override
    public int stopMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice existedDevice = hDeviceMapper.selectDeviceByApeId(apeId);
        if (existedDevice == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        if (isGbDevice(existedDevice)) {
            // 按设备归属平台停流（wvp_server_id=NULL 时回落默认平台 id=1）
            wvpClient.playStop(existedDevice.getWvp_server_id(), existedDevice.getGb_id(), existedDevice.getGb_channel_id());
            hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_STOPPED);
            hDeviceMapper.updatePlayUrlByApeId(apeId, null);
            return 1;
        }

        boolean directProxyDeleted = false;
        if (isDirectDevice(existedDevice) && StringUtils.isNotBlank(existedDevice.getZlm_proxy_key())) {
            try {
                directProxyDeleted = deleteDirectStreamProxy(existedDevice);
            } catch (Exception e) {
                log.error("调用ZLM delStreamProxy失败, apeId={}, key={}", apeId, existedDevice.getZlm_proxy_key(), e);
            }
        }

        int updated = hDeviceMapper.updateMonitorStateByApeId(apeId, MONITOR_STATUS_STOPPED);
        if (updated <= 0) {
            throw new ServiceException("停止监控失败: " + apeId);
        }

        hDeviceMapper.updatePlayUrlByApeId(apeId, null);
        if (directProxyDeleted) {
            hDeviceMapper.updateZlmProxyKeyByApeId(apeId, null);
        }
        return updated;
    }

    @Override
    public Map<String, Object> previewMonitor(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }

        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }

        if (isGbDevice(device)) {
            String gbPlayUrl = device.getPlay_url();
            if (StringUtils.isBlank(gbPlayUrl)) {
                Map<String, Object> gb = buildGb28181Play(device);
                gbPlayUrl = String.valueOf(gb.getOrDefault("playUrl", ""));
            }
            Map<String, Object> gbResult = new HashMap<>();
            gbResult.put("apeId", device.getApe_id());
            gbResult.put("name", device.getName());
            gbResult.put("streamSourceType", device.getStream_source_type());
            gbResult.put("deviceType", device.getDevice_type());
            gbResult.put("monitorStatus", device.getMonitor_status());
            gbResult.put("playUrl", gbPlayUrl);
            gbResult.put("gbId", device.getGb_id());
            gbResult.put("gbChannelId", device.getGb_channel_id());
            gbResult.put("status", device.getStatus());
            gbResult.put("supportedMonitorStatuses", new String[] {
                MONITOR_STATUS_RUNNING,
                MONITOR_STATUS_STOPPED,
                MONITOR_STATUS_STARTING,
                MONITOR_STATUS_STOPPING,
                MONITOR_STATUS_ERROR
            });
            return gbResult;
        }

        String previewAddProxyUrl = buildDirectAddProxyUrl(device);
        String previewPlayUrl = device.getPlay_url();
        if (StringUtils.isBlank(previewPlayUrl)) {
            previewPlayUrl = buildDirectPlayUrl(device);
        }
        if (StringUtils.isBlank(previewPlayUrl)) {
            previewPlayUrl = device.getDirect_source_url();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("apeId", device.getApe_id());
        result.put("name", device.getName());
        result.put("streamSourceType", device.getStream_source_type());
        result.put("monitorStatus", device.getMonitor_status());
        result.put("directSourceUrl", device.getDirect_source_url());
        result.put("playUrl", previewPlayUrl);
        result.put("ipAddr", device.getIp_addr());
        result.put("port", device.getPort());
        result.put("supportedMonitorStatuses", new String[] {
            MONITOR_STATUS_RUNNING,
            MONITOR_STATUS_STOPPED,
            MONITOR_STATUS_STARTING,
            MONITOR_STATUS_STOPPING,
            MONITOR_STATUS_ERROR
        });
        return result;
    }

    /**
     * 云台控制: 校验国标设备 → 按设备归属平台调 WVP PTZ(转动/变焦/停止)。
     */
    @Override
    public String ptzControl(String apeId, String command, Integer horizonSpeed, Integer verticalSpeed,
        Integer zoomSpeed) {
        HDevice device = requireGbDevice(apeId);
        if (StringUtils.isBlank(command)) {
            throw new ServiceException("云台控制 command 不能为空(left/right/up/down/upleft/upright/downleft/downright/zoomin/zoomout/stop)");
        }
        wvpClient.ptzControl(device.getWvp_server_id(), device.getGb_id(), device.getGb_channel_id(),
            command,
            horizonSpeed != null ? horizonSpeed : 100,
            verticalSpeed != null ? verticalSpeed : 100,
            zoomSpeed != null ? zoomSpeed : 8);
        return "云台控制已下发: " + command + " (" + device.getName() + ")";
    }

    /** 云台归位(回中)。 */
    @Override
    public String ptzHome(String apeId) {
        HDevice device = requireGbDevice(apeId);
        wvpClient.homePosition(device.getWvp_server_id(), device.getGb_id(), device.getGb_channel_id());
        return "云台归位已下发 (" + device.getName() + ")";
    }

    /** 调用预置位。 */
    @Override
    public String ptzPresetCall(String apeId, int presetId) {
        HDevice device = requireGbDevice(apeId);
        wvpClient.presetCall(device.getWvp_server_id(), device.getGb_id(), device.getGb_channel_id(), presetId);
        return "预置位调用已下发: preset " + presetId + " (" + device.getName() + ")";
    }

    /** 设置预置位。 */
    @Override
    public String ptzPresetAdd(String apeId, int presetId) {
        HDevice device = requireGbDevice(apeId);
        wvpClient.presetAdd(device.getWvp_server_id(), device.getGb_id(), device.getGb_channel_id(), presetId);
        return "预置位设置成功: preset " + presetId + " (" + device.getName() + ")";
    }

    /** 取国标设备并校验(不存在/非国标 → 抛错)。 */
    private HDevice requireGbDevice(String apeId) {
        if (StringUtils.isBlank(apeId)) {
            throw new ServiceException("apeId 不能为空");
        }
        HDevice device = hDeviceMapper.selectDeviceByApeId(apeId);
        if (device == null) {
            throw new ServiceException("设备不存在: " + apeId);
        }
        if (!isGbDevice(device)) {
            throw new ServiceException("云台控制仅支持 GB28181 设备: " + apeId);
        }
        if (StringUtils.isBlank(device.getGb_id()) || StringUtils.isBlank(device.getGb_channel_id())) {
            throw new ServiceException("设备国标编码/通道缺失，无法云台控制: " + apeId);
        }
        return device;
    }

    private boolean isDirectDevice(HDevice device) {
        return device != null && STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type());
    }

    private boolean isGbDevice(HDevice device) {
        return device != null && DEVICE_TYPE_GB28181.equalsIgnoreCase(device.getDevice_type());
    }

    /** 国标流 streamId = {设备}_{通道}（WVP 生成，已实测）。 */
    private String gbStreamId(HDevice device) {
        if (device == null) {
            return null;
        }
        return device.getGb_id() + "_" + StringUtils.nvl(device.getGb_channel_id(), "");
    }

    /** GB28181 点播：触发 WVP play → 返回 ws-flv，并回写 play_url / streamId。 */
    private Map<String, Object> buildGb28181Play(HDevice device) {
        if (StringUtils.isBlank(device.getGb_id()) || StringUtils.isBlank(device.getGb_channel_id())) {
            throw new ServiceException("国标设备缺少 gb_id / gb_channel_id");
        }
        // 按设备归属平台点播（wvp_server_id=NULL 时回落默认平台 id=1）
        Map<String, Object> play = wvpClient.playStart(device.getWvp_server_id(), device.getGb_id(), device.getGb_channel_id());
        String streamId = String.valueOf(play.getOrDefault("stream", ""));
        String wsFlv = String.valueOf(play.getOrDefault("wsFlv", ""));
        if (StringUtils.isBlank(wsFlv)) {
            // 兜底按约定拼 ws://zlm:9992/rtp/{streamId}.live.flv
            ZlmServer z = resolveEnabledZlmServer(device);
            if (z != null && StringUtils.isNotBlank(streamId)) {
                wsFlv = "ws://" + z.getHost() + ":" + z.getMedia_http_port() + "/rtp/" + streamId + ".live.flv";
            }
        }
        // 跨机可播: WVP 返回地址的 host 按其 media.ip(可能为 127.0.0.1)，统一重写为 zlm_server.host
        wsFlv = rewriteWsFlvHost(wsFlv, device);
        if (StringUtils.isNotBlank(wsFlv)) {
            hDeviceMapper.updatePlayUrlByApeId(device.getApe_id(), wsFlv);
        }
        Map<String, Object> r = new HashMap<>();
        r.put("apeId", device.getApe_id());
        r.put("playUrl", wsFlv);
        r.put("streamId", streamId);
        r.put("monitorStatus", MONITOR_STATUS_RUNNING);
        return r;
    }

    /** 把 ws-flv 地址 host 重写为 zlm_server.host（WVP 返回可能带 127.0.0.1/localhost）。 */
    private String rewriteWsFlvHost(String wsFlv, HDevice device) {
        if (StringUtils.isBlank(wsFlv)) {
            return wsFlv;
        }
        ZlmServer z = resolveEnabledZlmServer(device);
        if (z == null || StringUtils.isBlank(z.getHost())) {
            return wsFlv;
        }
        String lower = wsFlv.toLowerCase();
        if (lower.startsWith("ws://127.0.0.1") || lower.startsWith("ws://localhost")
            || lower.startsWith("ws://0.0.0.0")) {
            return wsFlv.replaceFirst("(?i)^ws://[^:/]+", "ws://" + z.getHost());
        }
        return wsFlv;
    }

    /** 判定国标流是否上线：ZLM getMediaInfo(app=rtp, stream={设备}_{通道}) code==0。 */
    private boolean zlmStreamOnline(HDevice device) {
        if (StringUtils.isBlank(device.getGb_id())) {
            return false;
        }
        String stream = gbStreamId(device);
        ZlmServer z = resolveEnabledZlmServer(device);
        if (z == null || StringUtils.isBlank(z.getHost()) || z.getApi_port() == null) {
            return false;
        }
        String url = UriComponentsBuilder.fromUriString("http://" + z.getHost() + ":" + z.getApi_port() + "/index/api/getMediaInfo")
            .queryParam("secret", StringUtils.nvl(z.getSecret(), ""))
            .queryParam("vhost", "__defaultVhost__")
            .queryParam("app", "rtp")
            .queryParam("schema", "rtsp")
            .queryParam("stream", stream)
            .build(true).toUriString();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(restTemplate.getForEntity(url, String.class).getBody());
            return parseCode(root.path("code").asText()) == 0;
        } catch (Exception e) {
            log.warn("查询ZLM国标流失败 stream={} err={}", stream, e.getMessage());
            return false;
        }
    }

    /**
     * 定时同步：从 WVP 拉设备+通道 → upsert h_device；再用 ZLM getMediaList 判定在线。
     * 采用轮询（不接管 ZLM hook，避免破坏 WVP 鉴权/点播链路）。
     */
    @Scheduled(initialDelay = 15000L, fixedDelay = 10000L)
    public void syncGbDevices() {
        try {
            syncGbDevicesOnce();
        } catch (Exception e) {
            log.warn("同步国标设备失败: {}", e.getMessage());
        }
    }

    /** 供手动调用 / 测试复用。 */
    public void syncGbDevicesOnce() {
        com.fasterxml.jackson.databind.JsonNode devices = wvpClient.listDevices();
        if (devices == null || !devices.isArray()) {
            return;
        }
        for (com.fasterxml.jackson.databind.JsonNode d : devices) {
            String uId = d.path("ID").asText("");
            if (StringUtils.isBlank(uId)) {
                continue;
            }
            boolean online = d.path("Online").asBoolean(false);
            HDevice existing = hDeviceMapper.selectByGbId(uId);
            HDevice row = new HDevice();
            row.setApe_id(uId);
            row.setGb_id(uId);
            row.setName(StringUtils.nvl(d.path("Name").asText(""), uId));
            row.setChannel_count(d.path("ChannelCount").asInt(0));
            row.setStatus(online ? GB_STATUS_ONLINE : GB_STATUS_OFFLINE);
            row.setIs_online(online ? "1" : "0");
            row.setSip_server("wvp");
            if (existing == null) {
                hDeviceMapper.upsertGbDevice(row);
            } else {
                // 已存在（可能来自 WVP 或手工）→ 更新，避免重复插入（h_device.ape_id 非唯一键）
                row.setApe_id(existing.getApe_id());
                hDeviceMapper.updateDevice(row);
            }
            if (online) {
                syncGbChannels(uId);
            }
        }
        // 注意：设备"上线/离线"以 WVP Online 为准；不得用"ZLM 是否有流"覆盖（无流≠设备离线）。
    }

    /** 拉某设备的通道，取第一个通道写入 gb_channel_id（一个设备通常一个摄像头通道）。 */
    private void syncGbChannels(String deviceId) {
        try {
            com.fasterxml.jackson.databind.JsonNode channels = wvpClient.listChannels(deviceId);
            if (channels == null || !channels.isArray() || channels.size() == 0) {
                return;
            }
            com.fasterxml.jackson.databind.JsonNode ch = channels.get(0);
            String channelId = ch.path("ID").asText("");
            if (StringUtils.isBlank(channelId)) {
                return;
            }
            HDevice update = new HDevice();
            update.setApe_id(deviceId);
            update.setGb_channel_id(channelId);
            update.setChannel_count(channels.size());
            update.setIs_online(ch.path("DeviceOnline").asBoolean(false) ? "1" : "0");
            update.setStatus(ch.path("DeviceOnline").asBoolean(false) ? GB_STATUS_ONLINE : GB_STATUS_OFFLINE);
            hDeviceMapper.updateDevice(update);
        } catch (Exception e) {
            log.warn("同步国标通道失败 deviceId={} err={}", deviceId, e.getMessage());
        }
    }

    /** 用 ZLM 流在线情况刷新国标设备在线状态（双源判定）。 */
    private void refreshGbOnlineStatus() {
        HDevice query = new HDevice();
        query.setDevice_type(DEVICE_TYPE_GB28181);
        List<HDevice> gbDevices = hDeviceMapper.selectDeviceList(query);
        if (gbDevices == null) {
            return;
        }
        for (HDevice d : gbDevices) {
            if (StringUtils.isBlank(d.getGb_id())) {
                continue;
            }
            boolean streamOnline = zlmStreamOnline(d);
            String newStatus = streamOnline ? GB_STATUS_ONLINE : GB_STATUS_OFFLINE;
            String newIsOnline = streamOnline ? "1" : "0";
            if (!newStatus.equals(StringUtils.nvl(d.getStatus(), ""))) {
                hDeviceMapper.updateStatusByApeId(d.getApe_id(), newStatus, newIsOnline);
            }
        }
    }

    private String normalizeOrgIndex(String orgIndex) {
        if (StringUtils.isBlank(orgIndex)) {
            return orgIndex;
        }

        String trimmed = orgIndex.trim();
        if (!trimmed.matches("\\d+")) {
            return orgIndex;
        }

        try {
            SysDept dept = sysDeptMapper.selectDeptById(Long.valueOf(trimmed));
            if (dept != null && StringUtils.isNotBlank(dept.getOrgIndex())) {
                return dept.getOrgIndex();
            }
        } catch (NumberFormatException ex) {
            log.warn("org_index 不是有效 deptId，按组织编码原样使用: {}", trimmed);
            return orgIndex;
        }

        return orgIndex;
    }

    private String sanitizeStreamName(String apeId) {
        String stream = STREAM_NAME_PATTERN.matcher(apeId == null ? "" : apeId).replaceAll("");
        if (StringUtils.isBlank(stream)) {
            return "cam" + System.currentTimeMillis();
        }
        return stream;
    }

    private String buildDirectAddProxyUrl(HDevice device) {
        if (device == null || !STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())
            || StringUtils.isBlank(device.getDirect_source_url())) {
            return "";
        }

        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null) {
            return "";
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();
        String stream = sanitizeStreamName(device.getApe_id());
        return UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/addStreamProxy")
            .queryParam("vhost", "__defaultVhost__")
            .queryParam("app", zlmApp)
            .queryParam("stream", stream)
            .queryParam("url", device.getDirect_source_url())
            .queryParam("enable_mp4", 1)
            .queryParam("auto_close", 0)
            .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                ? java.util.Optional.of(zlmServer.getSecret())
                : java.util.Optional.empty())
            .build(true)
            .toUriString();
    }

    private String buildDirectPlayUrl(HDevice device) {
        if (device == null || !STREAM_SOURCE_TYPE_DIRECT.equalsIgnoreCase(device.getStream_source_type())) {
            return "";
        }

        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getMedia_http_port() == null) {
            return "";
        }

        String zlmApp = StringUtils.isBlank(zlmServer.getApp()) ? DEFAULT_ZLM_APP : zlmServer.getApp().trim();
        String stream = sanitizeStreamName(device.getApe_id());
        return "ws://" + zlmServer.getHost() + ":" + zlmServer.getMedia_http_port() + "/" + zlmApp + "/" + stream + ".live.flv";
    }

    private ZlmServer resolveEnabledZlmServer(HDevice device) {
        if (device == null) {
            return null;
        }
        Long zlmServerId = device.getZlm_server_id() == null ? DEFAULT_SERVER_ID : device.getZlm_server_id();
        return zlmServerMapper.selectEnabledById(zlmServerId);
    }

    private boolean deleteDirectStreamProxy(HDevice device) {
        ZlmServer zlmServer = resolveEnabledZlmServer(device);
        if (zlmServer == null || StringUtils.isBlank(zlmServer.getHost()) || zlmServer.getApi_port() == null) {
            log.error("删除代理流失败，设备未绑定可用ZLM服务器或配置缺失, apeId={}", device.getApe_id());
            return false;
        }

        String delProxyUrl = UriComponentsBuilder
            .fromUriString("http://" + zlmServer.getHost() + ":" + zlmServer.getApi_port() + "/index/api/delStreamProxy")
            .queryParam("key", device.getZlm_proxy_key())
            .queryParamIfPresent("secret", StringUtils.isNotBlank(zlmServer.getSecret())
                ? java.util.Optional.of(zlmServer.getSecret())
                : java.util.Optional.empty())
            .build(true)
            .toUriString();

        if (log.isDebugEnabled()) {
            log.debug("调用ZLM delStreamProxy, apeId={}, url={}", device.getApe_id(), maskSensitiveUrl(delProxyUrl));
        }

        ResponseEntity<String> response = restTemplate.getForEntity(delProxyUrl, String.class);
        String body = response.getBody();
        if (StringUtils.isBlank(body)) {
            log.error("调用 ZLM delStreamProxy 返回空响应, apeId={}", device.getApe_id());
            return false;
        }

        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            int code = parseCode(root.path("code").asText());
            boolean flag = root.path("data").path("flag").asBoolean(false);
            if (code == 0 && flag) {
                return true;
            }
            String msg = root.path("msg").asText("");
            log.error("调用 ZLM delStreamProxy 失败, apeId={}, key={}, code={}, flag={}, msg={}",
                device.getApe_id(), device.getZlm_proxy_key(), code, flag, msg);
        } catch (Exception e) {
            log.error("调用 ZLM delStreamProxy 响应解析异常, apeId={}, key={}",
                device.getApe_id(), device.getZlm_proxy_key(), e);
        }
        return false;
    }

    private boolean isAddProxyAlreadyExists(String msg) {
        if (StringUtils.isBlank(msg)) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("already exists");
    }

    private int parseCode(Object code) {
        if (code instanceof Number) {
            return ((Number) code).intValue();
        }
        if (code == null) {
            return -1;
        }
        try {
            return Integer.parseInt(String.valueOf(code));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String maskSensitiveUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        return url.replaceAll("(?i)([?&](secret|token|access_token|auth|sign|signature)=)[^&]*", "$1***");
    }
}
