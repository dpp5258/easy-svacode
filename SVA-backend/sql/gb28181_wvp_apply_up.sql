-- ============================================================================
-- easySVA 国标设备-平台归属(WVP) 迁移 —— 一键下发/切换平台支持
-- 日期: 2026-09-07  配套回滚: gb28181_wvp_apply_down.sql
--
-- 背景(前端"国标平台(WVP)设置" -> "设为全部国标设备平台"):
--   现有国标设备行(h_device.device_type='GB28181')与 WVP 平台(wvp_server)之间
--   无归属关系; 运行时(WvpClient/GbSimulatorService)写死取 wvp_server id=1。
--   本迁移加 h_device.wvp_server_id 绑定列(NULL=未显式绑定, 运行时回落默认平台1),
--   使平台设置弹窗可"一键把某平台设为所有国标设备的归属平台"、播放/停止按归属
--   平台路由(多 WVP / 平台切换)。
--
-- 原则: 纯增量加列, 不动既有数据; 可回滚。
-- 执行前请先: mysqldump -uroot -peasySVA.EZ easySVA h_device > h_device-pre-wvpbind.sql
-- ============================================================================

ALTER TABLE `h_device`
    ADD COLUMN `wvp_server_id` BIGINT NULL DEFAULT NULL COMMENT '国标设备归属WVP平台(wvp_server.id); NULL=未绑定回落默认平台1' AFTER `sip_server`,
    ADD KEY `idx_hdevice_wvp_server_id` (`wvp_server_id`);
