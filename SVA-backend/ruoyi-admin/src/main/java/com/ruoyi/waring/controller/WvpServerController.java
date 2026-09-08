package com.ruoyi.waring.controller;

import java.util.List;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.waring.domain.WvpServer;
import com.ruoyi.waring.mapper.HDeviceMapper;
import com.ruoyi.waring.mapper.WvpServerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WVP-GB28181 平台连接配置管理（前端"国标平台设置"页）。
 * 说明：password 按 WvpClient 登录约定存储（md5 形式），编辑时不回显明文。
 * 另：/{id}/apply 实现"把该平台一键设为所有国标设备的归属平台"(h_device.wvp_server_id)，
 *     播放/停止等运行时按设备归属平台路由；NULL=回落默认平台 id=1。
 */
@RestController
@RequestMapping("/waring/wvp")
public class WvpServerController extends BaseController {

    @Autowired
    private WvpServerMapper wvpServerMapper;

    @Autowired
    private HDeviceMapper hDeviceMapper;

    /** 平台列表 */
    @PreAuthorize("@ss.hasPermi('waring:device:query')")
    @GetMapping("/list")
    public TableDataInfo list(WvpServer wvp) {
        startPage();
        List<WvpServer> list = wvpServerMapper.selectWvpServerList(wvp);
        return getDataTable(list);
    }

    /** 单条 */
    @PreAuthorize("@ss.hasPermi('waring:device:query')")
    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(wvpServerMapper.selectWvpServerById(id));
    }

    /** 新增平台（password 建议填 WVP 登录口令的 md5；host/api_port 默认 127.0.0.1/18080 由前端填） */
    @PreAuthorize("@ss.hasPermi('waring:device:add')")
    @PostMapping
    public AjaxResult add(@RequestBody WvpServer wvp) {
        if (wvp == null || StringUtils.isBlank(wvp.getHost()) || wvp.getApi_port() == null) {
            return error("host 与 api_port 不能为空");
        }
        return toAjax(wvpServerMapper.insertWvpServer(wvp));
    }

    /** 修改平台 */
    @PreAuthorize("@ss.hasPermi('waring:device:edit')")
    @PutMapping
    public AjaxResult edit(@RequestBody WvpServer wvp) {
        if (wvp == null || wvp.getId() == null) {
            return error("id 不能为空");
        }
        return toAjax(wvpServerMapper.updateWvpServer(wvp));
    }

    /** 删除平台 */
    @PreAuthorize("@ss.hasPermi('waring:device:remove')")
    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        return toAjax(wvpServerMapper.deleteWvpServerById(id));
    }

    /** 一键下发：把该平台设为所有国标设备的归属平台（h_device.wvp_server_id=id），返回受影响台数。 */
    @PreAuthorize("@ss.hasPermi('waring:device:edit')")
    @PostMapping("/apply/{id}")
    public AjaxResult applyToDevices(@PathVariable Long id) {
        WvpServer s = wvpServerMapper.selectWvpServerById(id);
        if (s == null) {
            return error("平台不存在: id=" + id);
        }
        if (s.getEnabled() == null || s.getEnabled() != 1) {
            return error("平台「" + s.getName() + "」未启用，不能下发");
        }
        if (StringUtils.isBlank(s.getHost()) || s.getApi_port() == null) {
            return error("平台「" + s.getName() + "」host/api_port 不完整，不能下发");
        }
        int n = hDeviceMapper.updateWvpServerIdForAllGb(id);
        return success("已把平台「" + s.getName() + "」(id=" + id + ") 设为 " + n + " 台国标设备的归属平台");
    }
}
