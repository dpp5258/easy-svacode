package com.ruoyi.waring.controller;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.waring.domain.WvpServer;
import com.ruoyi.waring.mapper.WvpServerMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WVP 国标平台连接配置管理（前端 /waring/wvp）。
 */
@RestController
@RequestMapping("/waring/wvp")
public class WvpServerController extends BaseController {

    @Autowired
    private WvpServerMapper wvpServerMapper;

    @GetMapping("/list")
    public TableDataInfo list(WvpServer query) {
        startPage();
        List<WvpServer> list = wvpServerMapper.selectWvpServerList(query);
        return getDataTable(list);
    }

    @GetMapping("/{id}")
    public AjaxResult getInfo(@PathVariable Long id) {
        return success(wvpServerMapper.selectWvpServerById(id));
    }

    @PostMapping
    public AjaxResult add(@RequestBody WvpServer w) {
        if (w == null || w.getName() == null || w.getName().trim().isEmpty()) {
            return AjaxResult.error("name不能为空");
        }
        if (w.getHost() == null || w.getHost().trim().isEmpty() || w.getApi_port() == null) {
            return AjaxResult.error("host/api_port不能为空");
        }
        if (wvpServerMapper.countByName(w) > 0) {
            return AjaxResult.error("名称已存在: " + w.getName());
        }
        return toAjax(wvpServerMapper.insertWvpServer(w));
    }

    @PutMapping
    public AjaxResult edit(@RequestBody WvpServer w) {
        if (w == null || w.getId() == null) {
            return AjaxResult.error("id不能为空");
        }
        if (w.getName() != null && wvpServerMapper.countByName(w) > 0) {
            return AjaxResult.error("名称已存在: " + w.getName());
        }
        return toAjax(wvpServerMapper.updateWvpServer(w));
    }

    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id) {
        return toAjax(wvpServerMapper.deleteWvpServerById(id));
    }

    /** 把某平台设为所有国标设备的归属平台（h_device.sip_server=wvp 语义占位）。 */
    @PostMapping("/apply/{id}")
    public AjaxResult apply(@PathVariable Long id) {
        if (wvpServerMapper.selectWvpServerById(id) == null) {
            return AjaxResult.error("WVP服务器不存在");
        }
        return AjaxResult.success("已应用");
    }
}
