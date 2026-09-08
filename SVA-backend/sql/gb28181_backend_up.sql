-- ============================================================================
-- easySVA 后端升级迁移(GB28181 国标接入 + 睡岗参数预留) —— 融合版 v2
-- 日期: 2026-09-07  配套回滚: gb28181_backend_down.sql
--
-- 本版说明(相对队友 easy-repo 内 sql 的修正):
--   1) 字段对齐 WVP 源代码(HDeviceMapper.xml / HDevice.java):
--      队友旧版 sql 加的是 gb_device_id/gb_platform_id, 而 WVP 分支代码实际读写
--      gb_id/gb_channel_id/gb_domain/gb_password/channel_count/status/sip_server,
--      两者不一致会导致启动后查询报"Unknown column"。本版以代码为准。
--   2) 命名收敛: 睡岗算法元数据沿用现网 on_yolo11n_pose(av_algorithm id=19),
--      不注册 on_yolopose_sleep, 避免双轨。
--   3) 新增 wvp_server 建表(队友 sql 缺失)。
--   4) zlm_server 的 gb28181_enabled/gb_sip_port 开关属已废弃的 ZLM-gb-api 路线,
--      WVP 路线不需要, 不再建。
-- 原则: 全部增量(加列/加表), 不修改既有列与行; 可随时用 down 脚本回滚。
-- 执行前请先: mysqldump -uroot -peasySVA.EZ easySVA > easySVA-pre-gb-fusion.sql
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) h_device: GB28181 国标字段(与 HDevice.java / HDeviceMapper.xml 一一对应)
-- ----------------------------------------------------------------------------
ALTER TABLE `h_device`
    ADD COLUMN `device_type`    varchar(16)  NOT NULL DEFAULT 'RTSP'   COMMENT '设备类型: RTSP(拉流/直连)/GB28181(国标)' AFTER `stream_source_type`,
    ADD COLUMN `gb_id`          varchar(64)  NULL                      COMMENT '国标设备编码(20位, = WVP DeviceList.ID, 兼作 ape_id)' AFTER `device_type`,
    ADD COLUMN `gb_channel_id`  varchar(64)  NULL                      COMMENT '国标通道编码(摄像机, 点播/拉流用)' AFTER `gb_id`,
    ADD COLUMN `gb_domain`      varchar(64)  NULL                      COMMENT '国标 SIP 域' AFTER `gb_channel_id`,
    ADD COLUMN `gb_password`    varchar(128) NULL                      COMMENT '国标 SIP 摘要密码(存库, 不返前端)' AFTER `gb_domain`,
    ADD COLUMN `channel_count`  int(11)      NOT NULL DEFAULT 0        COMMENT '设备通道数' AFTER `gb_password`,
    ADD COLUMN `status`         varchar(16)  NOT NULL DEFAULT 'OFFLINE' COMMENT '国标在线状态: ONLINE/OFFLINE' AFTER `channel_count`,
    ADD COLUMN `sip_server`     varchar(64)  NULL                      COMMENT '所属 SIP 平台标识(如 WVP)' AFTER `status`,
    ADD KEY `idx_hdevice_type` (`device_type`),
    ADD UNIQUE KEY `uk_hdevice_gb_id` (`gb_id`);
-- 存量数据归一: 旧 RTSP 设备全部归 device_type='RTSP'(gb_id 为 NULL, 唯一索引允许多个 NULL, 不冲突)
UPDATE `h_device` SET `device_type` = 'RTSP' WHERE `device_type` IS NULL OR `device_type` = '';

-- ----------------------------------------------------------------------------
-- 2) wvp_server: WVP-PRO 连接配置表(WvpServer / WvpServerMapper.selectEnabledById)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `wvp_server` (
    `id`          bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`        varchar(64)           DEFAULT NULL    COMMENT '名称',
    `host`        varchar(64)  NOT NULL                 COMMENT 'WVP 主机地址',
    `api_port`    int(11)      NOT NULL                 COMMENT 'WVP REST 端口(默认 18080)',
    `username`    varchar(64)           DEFAULT NULL    COMMENT '登录用户名',
    `password`    varchar(128)          DEFAULT NULL    COMMENT '登录口令(按 WvpClient 约定编码)',
    `enabled`     tinyint(1)   NOT NULL DEFAULT '1'     COMMENT '是否启用',
    `create_time` datetime              DEFAULT NULL,
    `update_time` datetime              DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COMMENT='WVP-GB28181 平台连接配置';

-- 种子行(按实际 WVP 部署改 host/api_port/password 后启用; 幂等: id=1 已存在则跳过)
INSERT INTO `wvp_server` (`id`, `name`, `host`, `api_port`, `username`, `password`, `enabled`, `create_time`, `update_time`)
SELECT 1, 'default-wvp', '127.0.0.1', 18080, 'admin', '<wvp-md5-or-plain>', 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM `wvp_server` WHERE `id` = 1);

-- ----------------------------------------------------------------------------
-- 3) deployment_task_algorithm: 算法自定义参数预留(paramsJson 后端原样透传)
-- ----------------------------------------------------------------------------
ALTER TABLE `deployment_task_algorithm`
    ADD COLUMN `params_json` text NULL COMMENT '算法自定义参数(JSON), 后端原样透传分析器; 睡岗引擎参数走 behaviorRules 高级键, 本列预留';

-- ----------------------------------------------------------------------------
-- 4) 命名收敛: 睡岗算法元数据 = on_yolo11n_pose(现网已存在 id=19)
--    不注册队友旧版种子 on_yolopose_sleep, 避免算法下拉双轨。
--    (如需展示名统一, 可执行: UPDATE av_algorithm SET name='睡岗检测(YOLO-Pose 17点姿态)' WHERE code='on_yolo11n_pose';)
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- 5) 可选: 设备类型字典(前端下拉用; 与主流程无关, 不需要可跳过)
-- ----------------------------------------------------------------------------
INSERT INTO `sys_dict_type` (`dict_name`, `dict_type`, `status`, `create_by`, `create_time`, `remark`)
SELECT '设备类型', 'sva_device_type', '0', 'admin', NOW(), 'easySVA 设备类型(RTSP/GB28181)'
WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_type` WHERE `dict_type` = 'sva_device_type');

INSERT INTO `sys_dict_data` (`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `remark`)
SELECT 1, 'RTSP(主动拉流)', 'RTSP', 'sva_device_type', '', '', 'N', '0', 'admin', NOW(), '直接拉流摄像头' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_data` WHERE `dict_type` = 'sva_device_type' AND `dict_value` = 'RTSP');
INSERT INTO `sys_dict_data` (`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `remark`)
SELECT 2, 'GB28181(国标)', 'GB28181', 'sva_device_type', '', '', 'N', '0', 'admin', NOW(), '国标接入摄像头' FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_dict_data` WHERE `dict_type` = 'sva_device_type' AND `dict_value` = 'GB28181');

-- ----------------------------------------------------------------------------
-- 校验(应返回: h_device 8 列 / wvp_server 存在 / params_json 1)
-- ----------------------------------------------------------------------------
SELECT 'h_device' t, COUNT(*) cnt FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='h_device'
  AND COLUMN_NAME IN ('device_type','gb_id','gb_channel_id','gb_domain','gb_password','channel_count','status','sip_server')
UNION ALL SELECT 'wvp_server', COUNT(*) FROM information_schema.TABLES
WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='wvp_server'
UNION ALL SELECT 'params_json', COUNT(*) FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA='easySVA' AND TABLE_NAME='deployment_task_algorithm' AND COLUMN_NAME='params_json';
