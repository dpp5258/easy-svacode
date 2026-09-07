package com.ruoyi.waring.domain;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.*;

@Data
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class HDevice extends BaseEntity {
    @Excel(name = "设备编码")
    private String ape_id;
    @Excel(name = "设备名称")
    private String name;
    private String stream_source_type;
    private String direct_source_url;
    private String play_url;
    private String zlm_proxy_key;
    private String resource_type;
    private String sub_type;
    @Excel(name = "IP地址")
    private String ip_addr;
    @Excel(name = "端口号")
    private Integer port;
    private String org_index;
    private String org_name;
    private String place_code;
    private String place;
    private String is_online;
    private String producer;
    private String producer_name;
    private String parent_code;
    private Long zlm_server_id;
    private Long sva_server_id;
    @Excel(name = "监控状态")
    private String monitor_status;
    private String create_time;
    private String update_time;
    // GB28181 国标接入新增字段（只增不改）
    private String device_type;      // RTSP / GB28181
    private String gb_id;            // 国标设备ID(20位)
    private String gb_channel_id;    // 国标通道ID(摄像机)
    private String gb_domain;        // 国标SIP域
    private String gb_password;      // 国标SIP摘要密码(存库,不返前端)
    private Integer channel_count;   // 通道数
    private String status;           // ONLINE / OFFLINE
    private String sip_server;       // 所属SIP平台(WVP)标识
}
