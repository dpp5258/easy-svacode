package com.ruoyi.waring.startup;

import com.ruoyi.waring.domain.WvpServer;
import com.ruoyi.waring.domain.ZlmServer;
import com.ruoyi.waring.mapper.WvpServerMapper;
import com.ruoyi.waring.mapper.ZlmServerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * easySVA 启动自愈: 同机一键部署时自动把 wvp_server(id=1) / zlm_server(id=1) 的 host
 * 从 "127.0.0.1/localhost/空" 修正为"本机探测到的局域网 IP"(便于队友拷贝整套服务到
 * 新机器后直接启动, 无需手改库)。
 *
 * 规则:
 *   1) host 已是局域网 IP(非回环) → 不动(支持异地 WVP/ZLM 手工配置场景);
 *   2) host 是 127.0.0.1 / localhost / 0.0.0.0 / 空 → 更新为探测 IP(仅 id=1 两行, 其余不动);
 *   3) 探测 IP 可用配置覆盖: application.yml 里 easySva.selfHeal.host 显式指定
 *      (多网卡/需要固定某 IP 时用), 缺省自动取第一个非回环 IPv4。
 * 自愈失败只记日志, 绝不阻断启动。
 */
@Component
public class ServerHostSelfHealRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServerHostSelfHealRunner.class);

    private static final long DEFAULT_SERVER_ID = 1L;
    private static final String[] VIRTUAL_IFACE_PREFIXES = {
        "lo", "docker", "veth", "br-", "virbr", "vmnet", "tun", "tailscale", "zt", "vbox", "kube"
    };

    @Value("${easySva.selfHeal.host:}")
    private String overrideHost;

    @Autowired
    private WvpServerMapper wvpServerMapper;

    @Autowired
    private ZlmServerMapper zlmServerMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            String localIp = detectLocalIp();
            if (!StringUtils.hasText(localIp)) {
                log.warn("[启动自愈] 未探测到本机局域网 IPv4, 跳过 wvp_server/zlm_server host 自愈(可配置 easySva.selfHeal.host 指定)");
                return;
            }
            log.info("[启动自愈] 本机局域网 IP = {}", localIp);
            healWvpServerHost(localIp);
            healZlmServerHost(localIp);
        } catch (Exception e) {
            log.warn("[启动自愈] 执行异常(仅记录, 不影响启动): {}", e.getMessage(), e);
        }
    }

    private void healWvpServerHost(String localIp) {
        WvpServer wvp = wvpServerMapper.selectWvpServerById(DEFAULT_SERVER_ID);
        if (wvp == null) {
            log.info("[启动自愈] wvp_server id=1 不存在, 跳过");
            return;
        }
        String next = resolveHost(wvp.getHost(), localIp);
        if (next == null) {
            log.info("[启动自愈] wvp_server(id=1) host={} 已是有效地址, 不动", wvp.getHost());
            return;
        }
        WvpServer update = new WvpServer();
        update.setId(DEFAULT_SERVER_ID);
        update.setHost(next);
        wvpServerMapper.updateWvpServer(update);
        log.info("[启动自愈] wvp_server(id=1) host {} → {} (同机部署自动修正)", wvp.getHost(), next);
    }

    private void healZlmServerHost(String localIp) {
        ZlmServer zlm = zlmServerMapper.selectZlmServerById(DEFAULT_SERVER_ID);
        if (zlm == null) {
            log.info("[启动自愈] zlm_server id=1 不存在, 跳过");
            return;
        }
        String next = resolveHost(zlm.getHost(), localIp);
        if (next == null) {
            log.info("[启动自愈] zlm_server(id=1) host={} 已是有效地址, 不动", zlm.getHost());
            return;
        }
        ZlmServer update = new ZlmServer();
        update.setId(DEFAULT_SERVER_ID);
        update.setHost(next);
        zlmServerMapper.updateZlmServer(update);
        log.info("[启动自愈] zlm_server(id=1) host {} → {} (同机部署自动修正)", zlm.getHost(), next);
    }

    /** 返回 null 表示不需要改; 返回新 host 表示应更新。 */
    private String resolveHost(String current, String localIp) {
        String cur = current == null ? "" : current.trim().toLowerCase();
        if (!cur.isEmpty() && !cur.equals("localhost") && !cur.equals("127.0.0.1")
            && !cur.equals("0.0.0.0") && !cur.equals("::1")) {
            return null; // 已是具体地址(局域网IP或异地IP) → 保留手工配置
        }
        return localIp;
    }

    /**
     * 探测本机局域网 IPv4: 优先物理网卡名(eth0、enp、wl 等开头)上的站点地址,
     * 其次取任何非回环非链路本地 IPv4; 跳过 docker、veth、br- 等虚拟网卡。
     * 可用 easySva.selfHeal.host 显式覆盖。
     */
    private String detectLocalIp() throws Exception {
        if (StringUtils.hasText(overrideHost)) {
            return overrideHost.trim();
        }
        List<String> siteCandidates = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        if (nis == null) {
            return "";
        }
        for (NetworkInterface ni : Collections.list(nis)) {
            if (ni == null || !ni.isUp() || ni.isLoopback()) {
                continue;
            }
            String ifName = ni.getDisplayName() == null ? "" : ni.getDisplayName().toLowerCase();
            boolean virtual = isVirtualInterface(ifName);
            boolean physicalLike = ifName.startsWith("eth") || ifName.startsWith("en")
                || ifName.startsWith("ens") || ifName.startsWith("eno") || ifName.startsWith("enp")
                || ifName.startsWith("wl") || ifName.startsWith("wlan") || ifName.startsWith("bond")
                || ifName.startsWith("team") || ifName.startsWith("em");
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (!(a instanceof Inet4Address) || a.isLoopbackAddress()
                    || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
                    continue;
                }
                String host = a.getHostAddress();
                if (virtual) {
                    continue;
                }
                if (a.isSiteLocalAddress() && physicalLike) {
                    siteCandidates.add(host);
                } else if (a.isSiteLocalAddress()) {
                    fallback.add(host);
                }
            }
        }
        if (!siteCandidates.isEmpty()) {
            return siteCandidates.get(0);
        }
        if (!fallback.isEmpty()) {
            return fallback.get(0);
        }
        return "";
    }

    private boolean isVirtualInterface(String ifName) {
        for (String p : VIRTUAL_IFACE_PREFIXES) {
            if (ifName.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
