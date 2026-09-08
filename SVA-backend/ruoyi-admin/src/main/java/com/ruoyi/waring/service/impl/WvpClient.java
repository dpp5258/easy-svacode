package com.ruoyi.waring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.WvpServer;
import com.ruoyi.waring.mapper.WvpServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WVP-GB28181 平台 REST 客户端（easySVA 后端作为 WVP 的调用方）。
 * 端点以本项目部署的 wvp-pro-2.7.4 fork 实测为准：
 *   登录    GET /api/user/login?username=&password=            → token(accessToken)
 *   设备列表 GET /api/v1/device/list?start=&limit=&online=      → { DeviceList:[...] }
 *   通道列表 GET /api/v1/device/channellist?serial=设备&limit=  → { ChannelList:[...] }
 *   点播     GET /api/play/start/{设备}/{通道}                   → { code, data:{app,stream,ws_flv,rtsp,ip} }
 *   停止     GET /api/play/stop/{设备}/{通道}
 * 多平台：登录 token 按 wvp_server.id 分开缓存；点播/停止等带设备上下文的调用可传
 *         serverId(取 h_device.wvp_server_id)，null/缺省回落默认平台 id=1。
 */
@Service
public class WvpClient {

    private static final Logger log = LoggerFactory.getLogger(WvpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long DEFAULT_SERVER_ID = 1L;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private WvpServerMapper wvpServerMapper;

    /** 各平台登录 token（key=wvp_server.id，缺省=1）。 */
    private final ConcurrentHashMap<Long, String> accessTokens = new ConcurrentHashMap<>();

    private WvpServer server(Long serverId) {
        long sid = (serverId == null) ? DEFAULT_SERVER_ID : serverId;
        WvpServer s = wvpServerMapper.selectEnabledById(sid);
        if (s == null || StringUtils.isBlank(s.getHost()) || s.getApi_port() == null) {
            throw new ServiceException("未配置可用WVP服务器(id=" + sid + ")");
        }
        return s;
    }

    private String baseUrl(Long serverId) {
        WvpServer s = server(serverId);
        return "http://" + s.getHost() + ":" + s.getApi_port();
    }

    private long sidOf(Long serverId) {
        return (serverId == null) ? DEFAULT_SERVER_ID : serverId;
    }

    /** 取某平台 token；未缓存或为空则现场登录（与旧版"空 token 每次重登"行为一致，登录失败抛异常由上层兜底）。 */
    private String tokenOf(Long serverId) {
        long sid = sidOf(serverId);
        String token = accessTokens.get(sid);
        if (StringUtils.isBlank(token)) {
            token = login0(sid);
        }
        return token;
    }

    /** 登录并缓存 token（默认平台）。WVP 非白名单接口需带 access-token 头。 */
    public String login() {
        return login0(DEFAULT_SERVER_ID);
    }

    private String login0(long sid) {
        WvpServer s = server(sid);
        String url = UriComponentsBuilder.fromUriString(baseUrl(sid) + "/api/user/login")
            .queryParam("username", s.getUsername())
            .queryParam("password", s.getPassword())
            .build(true).toUriString();
        JsonNode root = readJson(url, false, sid);
        String token = root.has("accessToken") ? root.get("accessToken").asText("") : "";
        if (StringUtils.isBlank(token) && root.has("data") && root.get("data").has("accessToken")) {
            token = root.get("data").get("accessToken").asText("");
        }
        accessTokens.put(sid, token);
        if (log.isDebugEnabled()) {
            log.debug("WVP login sid={} tokenLen={}", sid, token.length());
        }
        return token;
    }

    /** 拉取 WVP 国标设备列表（默认平台，同步用；依赖此 list 服务，网络/鉴权异常抛 ServiceException 由上层兜底）。
     * 注意：该 fork 的 list 接口【不传 start/limit】时走 getAllByStatus 返回全部设备；
     *        传了 start/limit 会走分页(start/limit 被当 page)导致列表为空。故此处不带分页参数。 */
    public JsonNode listDevices() {
        String url = baseUrl(null) + "/api/v1/device/list";
        JsonNode root = readJson(url, true, null);
        return root.path("DeviceList");
    }

    /** 拉取某设备的通道列表（默认平台）。 */
    public JsonNode listChannels(String deviceId) {
        String url = UriComponentsBuilder.fromUriString(baseUrl(null) + "/api/v1/device/channellist")
            .queryParam("serial", deviceId)
            .queryParam("limit", 1000)
            .build(true).toUriString();
        JsonNode root = readJson(url, true, null);
        return root.path("ChannelList");
    }

    /** 点播（默认平台）：触发 INVITE + ZLM 开收流口；返回 {app, stream, wsFlv, rtsp, ip}。 */
    public Map<String, Object> playStart(String deviceId, String channelId) {
        return playStart(null, deviceId, channelId);
    }

    /** 点播（指定归属平台，serverId=null 回落默认平台）。 */
    public Map<String, Object> playStart(Long serverId, String deviceId, String channelId) {
        String url = baseUrl(serverId) + "/api/play/start/" + deviceId + "/" + channelId;
        JsonNode data = readJson(url, true, serverId).path("data");
        Map<String, Object> m = new HashMap<>();
        m.put("app", data.path("app").asText(""));
        m.put("stream", data.path("stream").asText(""));
        m.put("wsFlv", data.path("ws_flv").asText(""));
        m.put("rtsp", data.path("rtsp").asText(""));
        m.put("ip", data.path("ip").asText(""));
        return m;
    }

    /** 停止点播（默认平台）。 */
    public void playStop(String deviceId, String channelId) {
        playStop(null, deviceId, channelId);
    }

    /** 停止点播（指定归属平台）。 */
    public void playStop(Long serverId, String deviceId, String channelId) {
        if (StringUtils.isBlank(deviceId)) {
            return;
        }
        String url = baseUrl(serverId) + "/api/play/stop/" + deviceId + "/" + channelId;
        try {
            readJson(url, true, serverId);
        } catch (Exception e) {
            log.warn("WVP playStop失败 sid={} deviceId={} channelId={} err={}",
                sidOf(serverId), deviceId, channelId, e.getMessage());
        }
    }

    /**
     * 登记国标设备到 WVP（默认平台；供"国标设备登记"用；设备仍需自己 REGISTER 上线）。
     * WVP 2.7.4 实测端点: POST /api/device/query/device/add
     */
    public void deviceAdd(String deviceId, String name, String password) {
        String url = baseUrl(null) + "/api/device/query/device/add";
        Map<String, Object> body = new HashMap<>();
        body.put("deviceId", deviceId);
        body.put("name", StringUtils.isBlank(name) ? deviceId : name);
        body.put("transport", "UDP");
        body.put("charset", "GB2312");
        body.put("password", StringUtils.isBlank(password) ? "12345678" : password);
        JsonNode root = postJson(url, body, true, null);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            String msg = root.path("msg").asText("");
            // 已存在视为成功（幂等）
            if (msg != null && (msg.contains("已存在") || msg.contains("exist"))) {
                return;
            }
            throw new ServiceException("WVP登记设备失败: " + msg + " (" + deviceId + ")");
        }
    }

    /**
     * 从 WVP 彻底删除国标设备(供"完整删除国标设备"用)。
     * WVP 2.7.4 实测端点: DELETE /api/device/query/devices/{deviceId}/delete
     * serverId=设备归属平台(null 回落默认 id=1); 设备已不存在视为删除成功(幂等)。
     */
    public void deleteDevice(Long serverId, String deviceId) {
        if (StringUtils.isBlank(deviceId)) {
            return;
        }
        String url = baseUrl(serverId) + "/api/device/query/devices/" + deviceId + "/delete";
        JsonNode root = deleteJson(url, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            String msg = root.path("msg").asText("");
            // 已不存在/已删除/不在线 → 幂等视为成功
            if (StringUtils.isNotBlank(msg)
                && (msg.contains("不存在") || msg.contains("not exist") || msg.contains("已删除")
                    || msg.contains("不存在该设备") || msg.contains("not found"))) {
                log.info("WVP删除设备幂等处理 deviceId={} msg={}", deviceId, msg);
                return;
            }
            throw new ServiceException("WVP删除设备失败(" + deviceId + "): " + msg);
        }
    }

    /**
     * 国标设备改名(供 easySVA "修改"国标设备用, 保证镜像不被 10s 同步回滚)。
     * 链路: GET /api/device/query/devices/{deviceId} 取全量对象(含内部 id)
     *      → 改 name → POST /api/device/query/device/update(实测在线设备也可改, code 0)。
     * serverId=设备归属平台(null 回落默认 id=1); WVP 无此设备/改名失败抛 ServiceException。
     */
    public void updateDeviceName(Long serverId, String deviceId, String name) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(name)) {
            return;
        }
        String deviceUrl = baseUrl(serverId) + "/api/device/query/devices/" + deviceId;
        JsonNode dev = readJson(deviceUrl, true, serverId).path("data");
        if (dev == null || dev.isNull() || StringUtils.isBlank(dev.path("deviceId").asText(""))) {
            throw new ServiceException("WVP 未找到该设备, 无法改名(可能已在 WVP 删除, 请删除本地记录): " + deviceId);
        }
        ObjectNode body = (ObjectNode) OBJECT_MAPPER.valueToTree(dev);
        body.put("name", name);
        JsonNode root = postJson(baseUrl(serverId) + "/api/device/query/device/update", body, true, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            throw new ServiceException("WVP 改名失败(" + deviceId + "): " + root.path("msg").asText(""));
        }
    }

    /**
     * 云台控制(转动/变焦/停止)。WVP 2.7.4 实测端点:
     *   GET /api/front-end/ptz/{deviceId}/{channelId}
     *     command: left,right,up,down,upleft,upright,downleft,downright,zoomin,zoomout,stop
     *     horizonSpeed(0-255) verticalSpeed(0-255) zoomSpeed(0-15)
     * serverId=设备归属平台(null 回落默认 id=1)。信令经 WVP SIP 下发, 不在线设备 WVP 侧会超时失败。
     */
    public void ptzControl(Long serverId, String deviceId, String channelId, String command,
                           Integer horizonSpeed, Integer verticalSpeed, Integer zoomSpeed) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(channelId) || StringUtils.isBlank(command)) {
            throw new ServiceException("云台控制参数不完整(deviceId/channelId/command)");
        }
        UriComponentsBuilder ub = UriComponentsBuilder
            .fromUriString(baseUrl(serverId) + "/api/front-end/ptz/" + deviceId + "/" + channelId)
            .queryParam("command", command);
        if (horizonSpeed != null) {
            ub.queryParam("horizonSpeed", horizonSpeed);
        }
        if (verticalSpeed != null) {
            ub.queryParam("verticalSpeed", verticalSpeed);
        }
        if (zoomSpeed != null) {
            ub.queryParam("zoomSpeed", zoomSpeed);
        }
        JsonNode root = readJson(ub.build(true).toUriString(), true, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            throw new ServiceException("WVP云台控制失败(" + command + "/" + deviceId + "): "
                + root.path("msg").asText(""));
        }
    }

    /** 调用预置位(可理解为"转动到预设角度")。WVP 2.7.4 实测: /api/front-end/preset/call/{deviceId}/{channelId} */
    public void presetCall(Long serverId, String deviceId, String channelId, int presetId) {
        String url = baseUrl(serverId) + "/api/front-end/preset/call/" + deviceId + "/" + channelId
            + "?presetId=" + presetId;
        JsonNode root = readJson(url, true, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            throw new ServiceException("WVP调用预置位失败(presetId=" + presetId + "/" + deviceId + "): "
                + root.path("msg").asText(""));
        }
    }

    /** 设置预置位(把当前位置存为指定编号)。WVP 2.7.4 实测: /api/front-end/preset/add/{deviceId}/{channelId} */
    public void presetAdd(Long serverId, String deviceId, String channelId, int presetId) {
        String url = baseUrl(serverId) + "/api/front-end/preset/add/" + deviceId + "/" + channelId
            + "?presetId=" + presetId;
        JsonNode root = readJson(url, true, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            throw new ServiceException("WVP设置预置位失败(presetId=" + presetId + "/" + deviceId + "): "
                + root.path("msg").asText(""));
        }
    }

    /** 云台回中/归位。WVP 2.7.4 实测: /api/device/control/home_position?deviceId=&channelId=&enabled=true */
    public void homePosition(Long serverId, String deviceId, String channelId) {
        if (StringUtils.isBlank(deviceId) || StringUtils.isBlank(channelId)) {
            return;
        }
        String url = UriComponentsBuilder
            .fromUriString(baseUrl(serverId) + "/api/device/control/home_position")
            .queryParam("deviceId", deviceId)
            .queryParam("channelId", channelId)
            .queryParam("enabled", true)
            .build(true).toUriString();
        JsonNode root = readJson(url, true, serverId);
        int code = root.has("code") ? root.path("code").asInt(-1) : -1;
        if (code != 0) {
            throw new ServiceException("WVP云台归位失败(" + deviceId + "): " + root.path("msg").asText(""));
        }
    }

    private JsonNode postJson(String url, Object body, boolean withAuth, Long serverId) {
        return exchangeWithRetry(url, HttpMethod.POST, new HttpEntity<Object>(body, buildHeaders(withAuth, serverId)), withAuth, serverId);
    }

    private JsonNode deleteJson(String url, Long serverId) {
        return exchangeWithRetry(url, HttpMethod.DELETE, new HttpEntity<String>(buildHeaders(true, serverId)), true, serverId);
    }

    private JsonNode readJson(String url, boolean withAuth, Long serverId) {
        return exchangeWithRetry(url, HttpMethod.GET, new HttpEntity<String>(buildHeaders(withAuth, serverId)), withAuth, serverId);
    }

    /** 组装请求头；withAuth=true 时带缓存 token（token 为空会现场登录）。 */
    private HttpHeaders buildHeaders(boolean withAuth, Long serverId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withAuth) {
            String token = tokenOf(serverId);
            if (StringUtils.isNotBlank(token)) {
                headers.set("access-token", token);
            }
        }
        return headers;
    }

    /** WVP 接口调用：401(token 过期/WVP 重启)时清缓存 token → 重新登录 → 用新 token 重试一次，避免跑 1 小时后全部 401。 */
    private JsonNode exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity, boolean withAuth, Long serverId) {
        return exchangeWithRetry(url, method, entity, withAuth, serverId, 0);
    }

    private JsonNode exchangeWithRetry(String url, HttpMethod method, HttpEntity<?> entity, boolean withAuth, Long serverId, int retried) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, method, entity, String.class);
            String respBody = resp.getBody();
            if (StringUtils.isBlank(respBody)) {
                throw new ServiceException("WVP接口返回空响应: " + url);
            }
            return OBJECT_MAPPER.readTree(respBody);
        } catch (ServiceException e) {
            throw e;
        } catch (HttpClientErrorException.Unauthorized e401) {
            if (withAuth && retried == 0) {
                Long sid = sidOf(serverId);
                accessTokens.remove(sid);
                log.info("WVP token 失效(401)，重新登录后重试: {}", url);
                login0(sid);
                HttpEntity<?> fresh = new HttpEntity<Object>(entity.getBody(), buildHeaders(true, serverId));
                return exchangeWithRetry(url, method, fresh, true, serverId, 1);
            }
            throw new ServiceException("调用WVP接口失败: " + url + " -> " + e401.getMessage());
        } catch (Exception e) {
            throw new ServiceException("调用WVP接口失败: " + url + " -> " + e.getMessage());
        }
    }
}
