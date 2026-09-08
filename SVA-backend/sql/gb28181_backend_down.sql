-- ============================================================================
-- easySVA 后端升级回滚(GB28181 国标接入 + 睡岗参数预留) —— 融合版 v2
-- 配套: gb28181_backend_up.sql
-- 说明: 仅删除 up 脚本新增的表/列/键/字典, 不影响既有数据。
-- ============================================================================

-- 字典(可选, 若执行过 up 第 5 节)
DELETE FROM `sys_dict_data`  WHERE `dict_type` = 'sva_device_type';
DELETE FROM `sys_dict_type`  WHERE `dict_type` = 'sva_device_type';

-- deployment_task_algorithm.params_json
ALTER TABLE `deployment_task_algorithm` DROP COLUMN `params_json`;

-- wvp_server 表
DROP TABLE IF EXISTS `wvp_server`;

-- h_device 国标字段与索引
ALTER TABLE `h_device`
    DROP KEY `uk_hdevice_gb_id`,
    DROP KEY `idx_hdevice_type`,
    DROP COLUMN `sip_server`,
    DROP COLUMN `status`,
    DROP COLUMN `channel_count`,
    DROP COLUMN `gb_password`,
    DROP COLUMN `gb_domain`,
    DROP COLUMN `gb_channel_id`,
    DROP COLUMN `gb_id`,
    DROP COLUMN `device_type`;
