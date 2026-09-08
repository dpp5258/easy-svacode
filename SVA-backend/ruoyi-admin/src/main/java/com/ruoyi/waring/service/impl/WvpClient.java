package com.ruoyi.waring.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * WVP-GB28181 平台 REST 客户端（easySVA 后端作为 WVP 的调用方）。
 * 端点以本项目部署的 wvp-pro-2.7.4 fork 实测为准：
 *   登录    GET /api/user/login?username=&password=            → token(accessToken)
 *   设备列表 GET /api/v1/device/list?start=&limit=&online=      → { DeviceList:[...] }
 *   通道列表 GET /api/v1/device/channellist?serial=设备&limit=  → { ChannelList:[...] }
 *   点播     GET /api/play/start/{设备}/{通道}                   → { code, data:{app,stream,ws_flv,rtsp,ip} }
 *   停止     GET /api/play/stop/{设备}/{通道}
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

    private volatile String accessToken;

    private WvpServer server() {
        WvpServer s = wvpServerMapper.selectEnabledById(DEFAULT_SERVER_ID);
        if (s == null || StringUtils.isBlank(s.getHost()) || s.getApi_port() == null) {
            throw new ServiceException("未配置可用WVP服务器");
        }
        return s;
    }

    private String baseUrl() {
        WvpServer s = server();
        return "http://" + s.getHost() + ":" + s.getApi_port();
    }

    /** 登录并缓存 token。WVP 非白名单接口需带 access-token 头。 */
    public String login() {
        WvpServer s = server();
        String url = UriComponentsBuilder.fromUriString(baseUrl() + "/api/user/login")
            .queryParam("username", s.getUsername())
            .queryParam("password", s.getPassword())
            .build(true).toUriString();
        JsonNode root = readJson(url, false);
        String token = root.has("accessToken") ? root.get("accessToken").asText("") : "";
        if (StringUtils.isBlank(token) && root.has("data") && root.get("data").has("accessToken")) {
            token = root.get("data").get("accessToken").asText("");
        }
        this.accessToken = token;
        if (log.isDebugEnabled()) {
            log.debug("WVP login url={} tokenLen={}", url, token.length());
        }
        return token;
    }

    /** 拉取 WVP 国标设备列表（依赖此 list 服务，网络/鉴权异常抛 ServiceException 由上层兜底）。
     * 注意：该 fork 的 list 接口【不传 start/limit】时走 getAllByStatus 返回全部设备；
     *        传了 start/limit 会走分页(start/limit 被当 page)导致列表为空。故此处不带分页参数。 */
    public JsonNode listDevices() {
        String url = baseUrl() + "/api/v1/device/list";
        JsonNode root = readJson(url, true);
        return root.path("DeviceList");
    }

    /** 拉取某设备的通道列表。 */
    public JsonNode listChannels(String deviceId) {
        String url = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/device/channellist")
            .queryParam("serial", deviceId)
            .queryParam("limit", 1000)
            .build(true).toUriString();
        JsonNode root = readJson(url, true);
        return root.path("ChannelList");
    }

    /** 点播：触发 INVITE + ZLM 开收流口；返回 {app, stream, wsFlv, rtsp, ip}。 */
    public Map<String, Object> playStart(String deviceId, String channelId) {
        String url = baseUrl() + "/api/play/start/" + deviceId + "/" + channelId;
        JsonNode data = readJson(url, true).path("data");
        Map<String, Object> m = new HashMap<>();
        m.put("app", data.path("app").asText(""));
        m.put("stream", data.path("stream").asText(""));
        m.put("wsFlv", data.path("ws_flv").asText(""));
        m.put("rtsp", data.path("rtsp").asText(""));
        m.put("ip", data.path("ip").asText(""));
        return m;
    }

    /** 停止点播。 */
    public void playStop(String deviceId, String channelId) {
        if (StringUtils.isBlank(deviceId)) {
            return;
        }
        String url = baseUrl() + "/api/play/stop/" + deviceId + "/" + channelId;
        try {
            readJson(url, true);
        } catch (Exception e) {
            log.warn("WVP playStop失败 deviceId={} channelId={} err={}", deviceId, channelId, e.getMessage());
        }
    }

    /** 云台控制（GB28181 方向/变焦）：WVP GET /api/front-end/ptz/{deviceId}/{channelId}。 */
    public Map<String, Object> ptzControl(String deviceId, String channelId, String command,
                                          Integer horizonSpeed, Integer verticalSpeed, Integer zoomSpeed) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl() + "/api/front-end/ptz/" + deviceId + "/" + channelId)
            .queryParam("command", command);
        if (horizonSpeed != null) b.queryParam("horizonSpeed", horizonSpeed);
        if (verticalSpeed != null) b.queryParam("verticalSpeed", verticalSpeed);
        if (zoomSpeed != null) b.queryParam("zoomSpeed", zoomSpeed);
        JsonNode root = readJson(b.build(true).toUriString(), true);
        Map<String, Object> m = new HashMap<>();
        m.put("code", root.path("code").asInt(0));
        return m;
    }

    /** 云台归位：WVP GET /api/device/control/home_position。 */
    public Map<String, Object> ptzHome(String deviceId, String channelId) {
        String url = UriComponentsBuilder.fromUriString(baseUrl() + "/api/device/control/home_position")
            .queryParam("deviceId", deviceId)
            .queryParam("channelId", channelId)
            .build(true).toUriString();
        JsonNode root = readJson(url, true);
        Map<String, Object> m = new HashMap<>();
        m.put("code", root.path("code").asInt(0));
        return m;
    }

    /** 预置位调用/设置：WVP GET /api/v1/control/preset?serial=..&code=..&command=call|add&preset=..。 */
    public Map<String, Object> ptzPreset(String deviceId, String channelId, Integer presetId, String command) {
        String url = UriComponentsBuilder.fromUriString(baseUrl() + "/api/v1/control/preset")
            .queryParam("serial", deviceId)
            .queryParam("code", channelId)
            .queryParam("command", command)
            .queryParam("preset", presetId)
            .build(true).toUriString();
        JsonNode root = readJson(url, true);
        Map<String, Object> m = new HashMap<>();
        m.put("code", root.path("code").asInt(0));
        return m;
    }

    private JsonNode readJson(String url, boolean withAuth) {
        return doGetJson(url, withAuth, true);
    }

    /** 带鉴权 GET；如遇 WVP token 过期(HTTP 401 或业务 code=401)自动重登一次再重试。 */
    private JsonNode doGetJson(String url, boolean withAuth, boolean allowRetry) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (withAuth) {
            String token = this.accessToken;
            if (StringUtils.isBlank(token)) {
                token = login();
            }
            if (StringUtils.isNotBlank(token)) {
                headers.set("access-token", token);
            }
        }
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET,
                new HttpEntity<String>(headers), String.class);
            String body = resp.getBody();
            if (StringUtils.isBlank(body)) {
                throw new ServiceException("WVP接口返回空响应: " + url);
            }
            JsonNode root = OBJECT_MAPPER.readTree(body);
            // 业务层 401("请登录后重新请求") → 清 token 重登重试一次
            if (withAuth && allowRetry && root.has("code") && root.path("code").asInt(0) == 401) {
                this.accessToken = null;
                return doGetJson(url, true, false);
            }
            return root;
        } catch (HttpClientErrorException hce) {
            if (withAuth && allowRetry && hce.getStatusCode().value() == 401) {
                this.accessToken = null;
                return doGetJson(url, true, false);
            }
            throw new ServiceException("调用WVP接口失败: " + url + " -> " + hce.getMessage());
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("调用WVP接口失败: " + url + " -> " + e.getMessage());
        }
    }
}
