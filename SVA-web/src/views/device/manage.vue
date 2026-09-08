<template>
  <div class="app-container">
    <div v-show="deviceListShow">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" label-width="68px">
      <el-form-item label="组织名称" prop="org_index">
        <el-select
          v-model="selectedQueryOrgIndex"
          filterable
          clearable
          placeholder="请选择组织名称"
          style="width: 240px"
          @change="handleQueryOrgChange"
        >
          <el-option
            v-for="item in queryDeptOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="设备编码" prop="ape_id">
        <el-input
          v-model="queryParams.ape_id"
          placeholder="请输入设备编码"
          clearable
          style="width: 240px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="设备名称" prop="name">
        <el-input
          v-model="queryParams.name"
          placeholder="请输入设备名称"
          clearable
          style="width: 240px"
          @keyup.enter.native="handleQuery"
        />
      </el-form-item>
      <el-form-item label="类型" prop="deviceType">
        <el-select v-model="queryParams.deviceType" placeholder="全部类型" clearable style="width: 140px" @change="handleQuery">
          <el-option v-for="item in deviceTypeOptions" :key="item.value || 'all'" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          v-hasPermi="['waring:device:add']"
        >新增RTSP</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-refresh"
          size="mini"
          @click="handleSyncGb"
          v-hasPermi="['waring:device:add']"
        >从WVP同步</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="warning"
          plain
          icon="el-icon-setting"
          size="mini"
          @click="openWvp"
          v-hasPermi="['waring:device:query']"
        >国标平台</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-circle-plus-outline"
          size="mini"
          @click="gbRegOpen = true"
          v-hasPermi="['waring:device:add']"
        >国标登记</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="success"
          plain
          icon="el-icon-edit"
          size="mini"
          :disabled="single"
          @click="handleUpdate"
          v-hasPermi="['waring:device:edit']"
        >修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button
          type="danger"
          plain
          icon="el-icon-delete"
          size="mini"
          :disabled="multiple"
          @click="handleDelete"
          v-hasPermi="['waring:device:remove']"
        >删除</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="deviceList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column label="设备编码" prop="ape_id" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="设备名称" prop="name" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="国标编码" prop="gb_id" align="center" :show-overflow-tooltip="true">
        <template slot-scope="scope">
          <span>{{ scope.row.gb_id || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="国标通道" prop="gb_channel_id" align="center" :show-overflow-tooltip="true">
        <template slot-scope="scope">
          <span>{{ scope.row.gb_channel_id || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="设备类型" prop="device_type" align="center">
        <template slot-scope="scope">
          <el-tag
            size="mini"
            :type="formatDeviceTagType(scope.row.device_type, scope.row.stream_source_type)"
          >{{ formatDeviceType(scope.row.device_type, scope.row.stream_source_type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="视频流地址" prop="direct_source_url" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="IP地址" prop="ip_addr" align="center" />
      <el-table-column label="端口" prop="port" align="center" />
      <el-table-column label="组织编码" prop="org_index" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="组织名称" prop="org_name" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="位置" prop="place" align="center" :show-overflow-tooltip="true" />
      <el-table-column label="在线状态" prop="is_online" align="center">
        <template slot-scope="scope">
          <el-tag size="mini" :type="onlineTagType(scope.row.is_online)">{{ renderOnline(scope.row.is_online) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" fixed="right" class-name="small-padding fixed-width operation-column" width="410">
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-video-play"
            @click="startMonitor(scope.row)"
            v-hasPermi="['waring:device:start']"
          >启动监控</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-video-pause"
            @click="stopMonitor(scope.row)"
            v-hasPermi="['waring:device:stop']"
          >停止监控</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-video-camera"
            @click="handlePreview(scope.row)"
            v-hasPermi="['waring:device:query']"
          >预览视频</el-button>
          <el-button
            v-if="String(scope.row.device_type || scope.row.stream_source_type || '').toUpperCase() === 'GB28181' || String(scope.row.gb_id || '').length === 20"
            size="mini"
            type="text"
            icon="el-icon-c-scale-to-original"
            @click="openPtzDialog(scope.row)"
            v-hasPermi="['waring:device:start']"
          >云台控制</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-zoom-in"
            @click="warningHistory(scope.row)"
            v-hasPermi="['waring:device:history']"
          >历史报警</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            v-hasPermi="['waring:device:edit']"
          >修改</el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            v-hasPermi="['waring:device:remove']"
          >删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page.sync="queryParams.pageNum"
      :limit.sync="queryParams.pageSize"
      @pagination="getList"
    />
    </div>

    <devicewarning
      v-show="!deviceListShow"
      @closeWarning="deviceListShow = true"
      :warningTitle="warningTitle"
      :device_id="device_id"
    />

    <player
      v-show="viewProof"
      :viewProof="viewProof"
      :rtspUrl="rtspUrl"
      title="实时监控预览"
      @closeProof="viewProof = false"
    />

    <!-- 云台控制弹窗（仅 GB28181 设备） -->
    <el-dialog :title="ptzTitle" :visible.sync="ptzOpen" width="360px" append-to-body :close-on-click-modal="false">
      <div style="text-align: center">
        <el-button type="text" icon="el-icon-video-play" @click="ptzPreviewToggle">
          {{ ptzPreviewing ? '关闭画面' : '预览画面' }}
        </el-button>
        <div style="margin: 6px 0 10px; font-size: 12px; color: #909399">
          {{ ptzDeviceName }}（{{ ptzGbId }}）
        </div>
      </div>

      <div style="display: flex; justify-content: center; align-items: center; user-select: none">
        <!-- 方向键 + 归位 -->
        <div style="display: grid; grid-template-columns: repeat(3, 52px); gap: 4px; margin-right: 18px">
          <div></div>
          <el-button size="small" icon="el-icon-caret-top" @mousedown.prevent="ptzPress('up')" @mouseup.prevent="ptzRelease('up')" @mouseleave="ptzRelease('up')" />
          <div></div>
          <el-button size="small" icon="el-icon-caret-left" @mousedown.prevent="ptzPress('left')" @mouseup.prevent="ptzRelease('left')" @mouseleave="ptzRelease('left')" />
          <el-button size="small" icon="el-icon-rank" @click="ptzHome" />
          <el-button size="small" icon="el-icon-caret-right" @mousedown.prevent="ptzPress('right')" @mouseup.prevent="ptzRelease('right')" @mouseleave="ptzRelease('right')" />
          <div></div>
          <el-button size="small" icon="el-icon-caret-bottom" @mousedown.prevent="ptzPress('down')" @mouseup.prevent="ptzRelease('down')" @mouseleave="ptzRelease('down')" />
          <div></div>
        </div>
        <!-- 变焦 + 预置位 -->
        <div style="text-align: center">
          <div style="margin-bottom: 6px">
            <el-button size="small" icon="el-icon-zoom-in" @mousedown.prevent="ptzPress('zoomin')" @mouseup.prevent="ptzRelease('zoomin')" @mouseleave="ptzRelease('zoomin')" />
          </div>
          <div>
            <el-button size="small" icon="el-icon-zoom-out" @mousedown.prevent="ptzPress('zoomout')" @mouseup.prevent="ptzRelease('zoomout')" @mouseleave="ptzRelease('zoomout')" />
          </div>
        </div>
      </div>

      <div style="margin-top: 12px; text-align: center; font-size: 12px; color: #909399">
        按住方向/变焦键连续转动，松开停止
      </div>

      <el-divider content-position="left">预置位</el-divider>
      <div style="text-align: center">
        <el-input-number v-model="ptzPresetId" :min="1" :max="255" size="mini" style="width: 110px" />
        <el-button size="mini" @click="ptzPreset('call')">转到</el-button>
        <el-button size="mini" type="primary" @click="ptzPreset('add')">存当前位置</el-button>
      </div>
    </el-dialog>

    <el-dialog :title="title" :visible.sync="open" width="620px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="90px">
        <el-row>
          <el-col :span="12">
            <el-form-item label="设备类型" prop="stream_source_type">
              <el-select v-model="form.stream_source_type" placeholder="请选择设备类型" style="width: 100%" @change="handleSourceTypeChange">
                <el-option v-for="item in streamSourceTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="设备编码" prop="ape_id">
              <el-input v-model="form.ape_id" :placeholder="form.stream_source_type === 'DIRECT' ? 'DIRECT 默认自动生成，可手动修改' : '请输入设备编码'">
                <el-button
                  v-if="form.stream_source_type === 'DIRECT' && !isEdit"
                  slot="append"
                  icon="el-icon-refresh"
                  @click="refreshApeId"
                >刷新</el-button>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="设备名称" prop="name">
              <el-input v-model="form.name" placeholder="请输入设备名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12" v-if="form.stream_source_type === 'DIRECT'">
            <el-form-item label="视频流地址" prop="direct_source_url">
              <el-input v-model="form.direct_source_url" placeholder="请输入视频流地址" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="组织名称" prop="org_name" :required="!isEdit">
              <el-select
                v-model="selectedOrgIndex"
                filterable
                clearable
                placeholder="请选择组织名称"
                style="width: 100%"
                @change="handleFormOrgChange"
              >
                <el-option
                  v-for="item in queryDeptOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="IP地址" prop="ip_addr" v-if="form.stream_source_type === 'PLATFORM'">
              <el-input v-model="form.ip_addr" placeholder="请输入IP地址" />
            </el-form-item>
            <el-form-item label="IP地址" prop="ip_addr" v-else>
              <el-input v-model="form.ip_addr" placeholder="可选" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="端口" prop="port" v-if="form.stream_source_type === 'PLATFORM'">
              <el-input v-model="form.port" placeholder="请输入端口" />
            </el-form-item>
            <el-form-item label="端口" prop="port" v-else>
              <el-input v-model="form.port" placeholder="可选" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="位置" prop="place" v-if="form.stream_source_type === 'PLATFORM'">
              <el-input v-model="form.place" placeholder="请输入位置" />
            </el-form-item>
            <el-form-item label="位置" prop="place" v-else>
              <el-input v-model="form.place" placeholder="可选" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="在线状态" prop="is_online">
              <el-select v-model="form.is_online" placeholder="请选择在线状态" style="width: 100%">
                <el-option v-for="item in onlineOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>

    <el-dialog title="国标平台(WVP)设置" :visible.sync="wvpOpen" width="760px" append-to-body>
      <el-form :model="wvp" :inline="true" label-width="90px" size="small">
        <el-form-item label="名称">
          <el-input v-model="wvp.name" placeholder="如 default-wvp" style="width: 160px" />
        </el-form-item>
        <el-form-item label="WVP地址">
          <el-input v-model="wvp.host" placeholder="127.0.0.1" style="width: 150px" />
        </el-form-item>
        <el-form-item label="REST端口">
          <el-input-number v-model="wvp.api_port" :min="1" :max="65535" controls-position="right" style="width: 130px" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="wvp.username" placeholder="admin" style="width: 130px" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="wvp.password" placeholder="WVP口令(留空不改)" show-password style="width: 160px" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="wvp.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="mini" @click="saveWvp">保 存</el-button>
          <el-button size="mini" @click="resetWvpForm">重 置</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="wvpLoading" :data="wvpList" size="mini" height="240" border>
        <el-table-column label="ID" prop="id" width="60" align="center" />
        <el-table-column label="名称" prop="name" align="center" />
        <el-table-column label="WVP地址" prop="host" align="center" />
        <el-table-column label="REST端口" prop="api_port" width="90" align="center" />
        <el-table-column label="用户名" prop="username" width="110" align="center" />
        <el-table-column label="启用" prop="enabled" width="70" align="center">
          <template slot-scope="scope">
            <el-tag size="mini" :type="scope.row.enabled === 1 ? 'success' : 'info'">{{ scope.row.enabled === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center">
          <template slot-scope="scope">
            <el-button size="mini" type="text" icon="el-icon-edit" @click="editWvp(scope.row)">编辑</el-button>
            <el-button size="mini" type="text" icon="el-icon-connection" :disabled="scope.row.enabled !== 1" @click="applyWvp(scope.row)">设为设备平台</el-button>
            <el-button size="mini" type="text" icon="el-icon-delete" @click="removeWvp(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div slot="footer" class="dialog-footer">
        <span style="float: left; font-size: 12px; color: #909399; line-height: 1.6">
          提示：默认 127.0.0.1:18080 / admin；密码按后端约定存 md5，编辑留空表示不改。<br/>
          「设为设备平台」= 把该平台一键设为<em>所有国标设备</em>的归属平台（写 h_device.wvp_server_id）；
          播放/停止按设备归属平台路由，未绑定时回落默认平台（现为 id=1）。<br/>
          注意：停用/不完整的平台不可下发；同步与国标登记仍按默认平台进行。
        </span>
        <el-button @click="wvpOpen = false">关 闭</el-button>
      </div>
    </el-dialog>

    <el-dialog title="国标设备登记 / 一键模拟器" :visible.sync="gbRegOpen" width="620px" append-to-body>
      <el-form :model="gbReg" label-width="100px" size="small">
        <el-form-item label="国标编码(20位)">
          <div style="display: flex">
            <el-input v-model="gbReg.deviceId" placeholder="如 34020000001320000005" maxlength="20" />
            <el-button type="text" style="margin-left: 6px" @click="genGbId">自动编号</el-button>
          </div>
        </el-form-item>
        <el-form-item label="设备名称">
          <el-input v-model="gbReg.name" placeholder="登记名/模拟设备名" />
        </el-form-item>
        <el-form-item label="通道编码(20位)">
          <el-input v-model="gbReg.channelId" placeholder="留空自动推导(第11-12位=23)" maxlength="20" />
        </el-form-item>
        <el-form-item label="SIP 口令">
          <el-input v-model="gbReg.password" placeholder="12345678" />
        </el-form-item>
        <el-form-item label="视频源(模拟器)">
          <el-select v-model="gbReg.source" style="width: 100%" filterable allow-create default-first-option clearable
            placeholder="选择预设，或输入自定义源(如 rtsp://…/ v4l2:///dev/video0)">
            <el-option v-for="item in gbSourceOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <div style="font-size: 12px; color: #909399; line-height: 1.6; margin-top: 4px">
            支持格式：<code>test</code>(彩条) / <code>file://…mp4</code>(H.264) / <code>rtsp://…</code> / <code>udp://ip:端口</code> / <code>v4l2:///dev/video0</code>
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <div style="font-size: 12px; color: #909399; line-height: 1.6">
            登记 = 在 WVP 里建设备记录（真机仍需在设备侧注册）；<br/>
            一键模拟器 = 在本机启动 sbgb28181 模拟该设备 REGISTER 上线（仅测试/演示，需 easySVA 与模拟器同机）。
          </div>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="success" size="mini" :loading="gbRegLoading" @click="doGbRegister">登记到WVP</el-button>
        <el-button type="primary" size="mini" :loading="gbRegLoading" @click="doGbSimStart">登记+启动模拟器</el-button>
        <el-button type="warning" size="mini" :loading="gbRegLoading" @click="doGbSimStop">停止模拟器</el-button>
        <el-button size="mini" @click="gbRegOpen = false">关 闭</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { getDeviceList, getDevice, addDevice, updateDevice, delDevice, startDeviceMonitor, stopDeviceMonitor, previewDeviceMonitor, syncGbDevices, listWvpServer, getWvpServer, addWvpServer, updateWvpServer, delWvpServer, applyWvpToDevices, registerGbDevice, gbSimStart, gbSimStop, ptzControl, ptzHome, ptzPresetCall, ptzPresetAdd } from '@/api/device'
import { deptTreeSelect } from '@/api/system/user'
import player from '@/components/RTSPPlayer'
import devicewarning from './components/device-warning.vue'

export default {
  name: 'DeviceManage',
  components: { devicewarning, player },
  data() {
    const validateDirectSourceUrl = (rule, value, callback) => {
      if (this.form.stream_source_type === 'DIRECT' && !value) {
        callback(new Error('DIRECT 设备类型下，视频流地址不能为空'))
        return
      }
      callback()
    }
    const validateOrgName = (rule, value, callback) => {
      if (!this.isEdit && !value) {
        callback(new Error('组织名称不能为空'))
        return
      }
      callback()
    }
    return {
      loading: false,
      total: 0,
      ids: [],
      single: true,
      multiple: true,
      open: false,
      title: '',
      warningTitle: '',
      device_id: '',
      deviceListShow: true,
      viewProof: false,
      rtspUrl: '',
      // ---- 云台控制状态 ----
      ptzOpen: false,
      ptzTitle: '云台控制',
      ptzDevice: null,
      ptzDeviceName: '',
      ptzGbId: '',
      ptzPresetId: 1,
      ptzPreviewing: false,
      ptzHoldTimer: null,
      ptzHoldCommand: '',
      isEdit: false,
      deptOptions: [],
      queryDeptOptions: [],
      selectedOrgIndex: undefined,
      selectedQueryOrgIndex: undefined,
      deviceList: [],
      streamSourceTypeOptions: [
        { value: 'DIRECT', label: '直连' },
        { value: 'PLATFORM', label: '平台' }
      ],
      deviceTypeOptions: [
        { value: undefined, label: '全部类型' },
        { value: 'RTSP', label: 'RTSP' },
        { value: 'GB28181', label: 'GB28181' }
      ],
      wvpOpen: false,
      wvpLoading: false,
      wvpList: [],
      wvp: { id: undefined, name: 'default-wvp', host: '127.0.0.1', api_port: 18080, username: 'admin', password: '', enabled: 1 },
      gbRegOpen: false,
      gbRegLoading: false,
      gbSourceOptions: [
        { value: 'file:///tmp/4_sleep_h264.mp4', label: '睡岗素材-4.mp4(转H264,3遍90s)' },
        { value: 'test', label: '彩条测试画面(videotestsrc)' },
        { value: 'file:///tmp/sleep_gb.mp4', label: '旧睡岗素材(/tmp/sleep_gb.mp4)' },
        { value: 'rtsp://127.0.0.1:9994/rtp/34020000001320000003_34020000002320000003', label: '示例: 转发本机ZLM国标流(rtsp)' },
        { value: 'v4l2:///dev/video0', label: '示例: 本机USB摄像头(v4l2)' }
      ],
      gbReg: { deviceId: '', name: '', channelId: '', password: '12345678', source: 'file:///tmp/4_sleep_h264.mp4' },
      onlineOptions: [
        { value: '0', label: '登录中' },
        { value: '1', label: '在线/启用' },
        { value: '2', label: '离线/停用' },
        { value: '9', label: '其他/异常' }
      ],
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        ape_id: undefined,
        name: undefined,
        org_index: undefined,
        deviceType: undefined
      },
      form: {},
      rules: {
        name: [{ required: true, message: '设备名称不能为空', trigger: 'blur' }],
        org_name: [{ validator: validateOrgName, trigger: 'change' }],
        direct_source_url: [{ validator: validateDirectSourceUrl, trigger: ['blur', 'change'] }]
      }
    }
  },
  watch: {
    ptzOpen(val) {
      if (!val) {
        this.ptzStopHold()
        this.ptzPreviewing = false
        this.ptzDevice = null
      }
    }
  },
  created() {
    this.getDeptTree()
    this.getList()
  },
  methods: {
    getDeptTree() {
      deptTreeSelect().then((response) => {
        this.deptOptions = response.data || []
        this.queryDeptOptions = this.buildQueryDeptOptions(this.deptOptions)
      })
    },
    buildQueryDeptOptions(nodes, parentLabel = '') {
      if (!Array.isArray(nodes) || nodes.length === 0) {
        return []
      }
      const options = []
      nodes.forEach((node) => {
        const value = node.org_index !== undefined && node.org_index !== null && node.org_index !== ''
          ? node.org_index
          : node.id
        const orgIndex = node.org_index !== undefined && node.org_index !== null && node.org_index !== ''
          ? node.org_index
          : value
        const currentLabel = node.label || node.deptName || node.org_name || ''
        const label = parentLabel && currentLabel ? `${parentLabel} / ${currentLabel}` : currentLabel

        if (value !== undefined && value !== null && value !== '' && label) {
          options.push({ value, label, orgIndex, orgName: currentLabel })
        }

        if (Array.isArray(node.children) && node.children.length > 0) {
          options.push(...this.buildQueryDeptOptions(node.children, label || parentLabel))
        }
      })
      return options
    },
    ensureFormOrgOption(orgIndex, orgName) {
      if (orgIndex === null || orgIndex === undefined || orgIndex === '') {
        return undefined
      }
      const normalizedOrgIndex = String(orgIndex)
      const matchedOption = this.queryDeptOptions.find((item) => String(item.orgIndex) === normalizedOrgIndex)
      if (matchedOption) {
        return matchedOption.value
      }
      const normalizedOrgName = orgName || ''
      let optionLabel = normalizedOrgIndex
      if (normalizedOrgName) {
        if (normalizedOrgName.includes('/')) {
          optionLabel = normalizedOrgName
        } else {
          const sameNameOption = this.queryDeptOptions.find((item) => item.orgName === normalizedOrgName)
          optionLabel = sameNameOption && sameNameOption.label ? sameNameOption.label : normalizedOrgName
        }
      }
      const tempOption = {
        value: normalizedOrgIndex,
        label: optionLabel,
        orgIndex: normalizedOrgIndex,
        orgName: normalizedOrgName
      }
      this.queryDeptOptions.push(tempOption)
      return tempOption.value
    },
    handleFormOrgChange(value) {
      if (value === null || value === undefined || value === '') {
        this.form.org_index = undefined
        this.form.org_name = undefined
        return
      }
      const option = this.queryDeptOptions.find((item) => String(item.value) === String(value))
      if (option) {
        this.form.org_index = option.orgIndex !== undefined && option.orgIndex !== null && option.orgIndex !== ''
          ? option.orgIndex
          : value
        this.form.org_name = option.orgName
        return
      }
      this.form.org_index = value
      this.form.org_name = undefined
    },
    handleQueryOrgChange(value) {
      this.queryParams.org_index = value === null || value === undefined || value === '' ? undefined : value
    },
    formatSourceType(value) {
      if (String(value).toUpperCase() === 'PLATFORM') {
        return '平台'
      }
      if (String(value).toUpperCase() === 'DIRECT') {
        return '直连'
      }
      return value || '直连'
    },
    renderOnline(value) {
      const normalized = String(value)
      const target = this.onlineOptions.find((item) => String(item.value) === normalized)
      return target ? target.label : value
    },
    onlineTagType(value) {
      // 1=在线/启用 绿; 2=离线/停用 红; 0=登录中 橙; 其余(9等)=灰
      const v = String(value)
      if (v === '1') return 'success'
      if (v === '2') return 'danger'
      if (v === '0') return 'warning'
      return 'info'
    },
    generateApeId() {
      const randomPart = Math.floor(100000 + Math.random() * 900000)
      return `cam${randomPart}`
    },
    refreshApeId() {
      this.form.ape_id = this.generateApeId()
    },
    handleSourceTypeChange(value) {
      const normalized = String(value || 'DIRECT').toUpperCase()
      this.form.stream_source_type = normalized
      if (normalized === 'DIRECT') {
        if (!this.isEdit && !this.form.ape_id) {
          this.form.ape_id = this.generateApeId()
        }
      } else {
        if (!this.isEdit) {
          this.form.ape_id = undefined
        }
        this.form.direct_source_url = undefined
      }
      this.$nextTick(() => {
        if (this.$refs.form) {
          this.$refs.form.clearValidate(['direct_source_url'])
        }
      })
    },
    getList() {
      this.loading = true
      getDeviceList(this.queryParams).then((response) => {
        this.deviceList = response.rows || []
        this.total = response.total || 0
        this.loading = false
      }).catch(() => {
        this.loading = false
      })
    },
    formatDeviceType(deviceType, streamSourceType) {
      const t = deviceType || ''
      if (String(t).toUpperCase() === 'GB28181') {
        return 'GB28181'
      }
      if (String(t).toUpperCase() === 'RTSP') {
        return 'RTSP'
      }
      return this.formatSourceType(streamSourceType)
    },
    formatDeviceTagType(deviceType, streamSourceType) {
      const t = String(deviceType || '').toUpperCase()
      if (t === 'GB28181') {
        return 'success'
      }
      if (t === 'RTSP') {
        return 'info'
      }
      return streamSourceType === 'PLATFORM' ? 'success' : 'info'
    },
    handleSyncGb() {
      this.$modal.confirm('确认从 WVP 同步一次国标设备？（设备将 upsert 到列表）').then(() => {
        return syncGbDevices()
      }).then(() => {
        this.$modal.msgSuccess('国标设备同步完成')
        this.getList()
      }).catch(() => {})
    },
    openWvp() {
      this.wvpOpen = true
      this.loadWvp()
    },
    loadWvp() {
      this.wvpLoading = true
      listWvpServer({ pageNum: 1, pageSize: 100 }).then((response) => {
        this.wvpList = response.rows || []
        this.wvpLoading = false
      }).catch(() => {
        this.wvpLoading = false
      })
    },
    resetWvpForm() {
      this.wvp = { id: undefined, name: 'default-wvp', host: '127.0.0.1', api_port: 18080, username: 'admin', password: '', enabled: 1 }
    },
    editWvp(row) {
      this.wvp = Object.assign({}, row)
      this.wvp.password = ''
    },
    saveWvp() {
      if (!this.wvp.host || !this.wvp.api_port) {
        this.$modal.msgWarning('WVP 地址与 REST 端口必填')
        return
      }
      const payload = Object.assign({}, this.wvp)
      const request = payload.id ? updateWvpServer(payload) : addWvpServer(payload)
      request.then(() => {
        this.$modal.msgSuccess(payload.id ? '平台已更新' : '平台已新增')
        this.resetWvpForm()
        this.loadWvp()
      }).catch(() => {})
    },
    removeWvp(row) {
      this.$modal.confirm('确认删除平台「' + row.name + '」？').then(() => {
        return delWvpServer(row.id)
      }).then(() => {
        this.$modal.msgSuccess('已删除')
        this.loadWvp()
      }).catch(() => {})
    },
    applyWvp(row) {
      this.$modal.confirm(
        `确认把所有国标设备(GB28181)的归属平台设为「${row.name}」(${row.host}:${row.api_port}, id=${row.id})？`,
        '设为设备平台'
      ).then(() => {
        return applyWvpToDevices(row.id)
      }).then((response) => {
        this.$modal.msgSuccess((response && response.msg) || '已下发')
        this.loadWvp()
      }).catch(() => {})
    },
    doGbRegister() {
      if (!this.gbReg.deviceId) {
        this.$modal.msgWarning('请填写 20 位国标编码')
        return
      }
      this.gbRegLoading = true
      registerGbDevice(this.gbReg).then((res) => {
        this.$modal.msgSuccess(res.msg || '已登记')
        this.getList()
      }).finally(() => {
        this.gbRegLoading = false
      })
    },
    genGbId() {
      getDeviceList({ pageNum: 1, pageSize: 200, deviceType: 'GB28181' }).then((response) => {
        // 20 位国标编号超过 JS Number 安全整数(2^53), 旧实现用 Number 会丢精度,
        // 可能算出"已存在的编号"→ 登记走幂等、不新建。改用 BigInt 精确自增。
        const ids = (response.rows || [])
          .map((row) => row.gb_id || row.ape_id)
          .filter((id) => /^\d{20}$/.test(id))
          .map((id) => BigInt(id))
        let next = ids.length ? ids.reduce((a, b) => (a > b ? a : b), 0n) + 1n : 34020000001320000001n
        // 防御: 若 +1 撞已有编号则继续递增(理论上 max+1 不会撞)
        while (ids.includes(next)) {
          next += 1n
        }
        const idStr = String(next)
        if (idStr.length !== 20) {
          this.$modal.msgError('国标编号已到 20 位上限(99999999999999999999)，无法自动生成')
          return
        }
        const id = idStr
        this.gbReg.deviceId = id
        if (!this.gbReg.name || /^模拟-/.test(this.gbReg.name)) {
          this.gbReg.name = '模拟-' + id
        }
        this.$modal.msgSuccess('已生成编码 ' + id + '（名称已自动填，可改）')
      })
    },
    doGbSimStart() {
      if (!this.gbReg.deviceId) {
        this.$modal.msgWarning('请填写 20 位国标编码')
        return
      }
      this.gbRegLoading = true
      gbSimStart(this.gbReg).then((res) => {
        const d = res.data || {}
        this.$modal.msgSuccess((d.msg || '模拟器已启动') + (d.pid ? ' (pid=' + d.pid + ')' : ''))
        setTimeout(() => {
          this.getList()
        }, 3000)
      }).finally(() => {
        this.gbRegLoading = false
      })
    },
    doGbSimStop() {
      if (!this.gbReg.deviceId) {
        this.$modal.msgWarning('请填写要停止的国标编码')
        return
      }
      this.gbRegLoading = true
      gbSimStop({ deviceId: this.gbReg.deviceId }).then((res) => {
        this.$modal.msgSuccess(res.msg || '已停止')
      }).finally(() => {
        this.gbRegLoading = false
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        ape_id: undefined,
        name: undefined,
        stream_source_type: 'DIRECT',
        direct_source_url: undefined,
        ip_addr: undefined,
        port: undefined,
        org_index: undefined,
        org_name: undefined,
        place: undefined,
        is_online: undefined
      }
      this.selectedOrgIndex = undefined
      this.viewProof = false
      this.rtspUrl = ''
      this.isEdit = false
      this.resetForm('form')
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm('queryForm')
      this.selectedQueryOrgIndex = undefined
      this.queryParams.org_index = undefined
      this.queryParams.deviceType = undefined
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.ape_id)
      this.single = selection.length !== 1
      this.multiple = selection.length === 0
    },
    handleAdd() {
      this.reset()
      if (this.form.stream_source_type === 'DIRECT') {
        this.form.ape_id = this.generateApeId()
      }
      this.open = true
      this.title = '新增设备'
    },
    handleUpdate(row) {
      this.reset()
      const apeId = row.ape_id || this.ids[0]
      if (!apeId) {
        return
      }
      getDevice(apeId).then((response) => {
        this.form = Object.assign({}, this.form, response.data || {})
        this.form.stream_source_type = (this.form.stream_source_type || 'DIRECT').toUpperCase()
        this.selectedOrgIndex = this.ensureFormOrgOption(this.form.org_index, this.form.org_name)
        this.handleFormOrgChange(this.selectedOrgIndex)
        this.open = true
        this.title = '修改设备'
        this.isEdit = true
      })
    },
    submitForm() {
      this.$refs.form.validate((valid) => {
        if (!valid) {
          return
        }
        this.form.stream_source_type = (this.form.stream_source_type || 'DIRECT').toUpperCase()
        const request = this.isEdit ? updateDevice(this.form) : addDevice(this.form)
        request.then(() => {
          this.$modal.msgSuccess(this.isEdit ? '修改成功' : '新增成功')
          this.open = false
          this.getList()
        })
      })
    },
    handleDelete(row) {
      // 注意: 工具栏按钮 @click="handleDelete" 会把鼠标事件当 row 传进来,
      // 所以必须用 row && row.ape_id 判断"行级删除", 否则事件对象被当 row → ape_id 为空 → 点了没反应。
      const singleApeId = row && row.ape_id ? row.ape_id : null
      const apeIds = singleApeId || this.ids
      const isBatch = !singleApeId
      if (!apeIds || (Array.isArray(apeIds) && apeIds.length === 0)) {
        return
      }
      const isGb = !isBatch && row.device_type === 'GB28181'
      const confirmMsg = isBatch
        ? '是否确认删除选中的 ' + apeIds.length + ' 台设备？'
        : isGb
          ? `国标设备「${apeIds}」将被彻底删除：停止同编号模拟器 → 从 WVP 移除 → 删除本地记录（10s 同步将不再恢复）。是否确认？`
          : '是否确认删除设备编号为"' + apeIds + '"的数据项？'
      this.$modal.confirm(confirmMsg).then(() => {
        return delDevice(apeIds)
      }).then((response) => {
        this.getList()
        const rmsg = response && response.msg && response.msg !== '操作成功' ? response.msg
          : (isGb ? '删除成功（已含 WVP/模拟器清理）' : '删除成功')
        this.$modal.msgSuccess(rmsg)
      }).catch(() => {})
    },
    async startMonitor(row) {
      try {
        const response = await startDeviceMonitor(row.ape_id)
        const payload = response && response.data && typeof response.data === 'object' ? response.data : {}
        const shortMessage = payload.shortMessage || '已启动监控，请到“实时监控”菜单继续操作。'
        const hasSuccess = Object.prototype.hasOwnProperty.call(payload, 'success')
        this.$message({
          type: hasSuccess && !payload.success ? 'warning' : 'success',
          message: shortMessage
        })
      } catch (error) {
        this.$modal.msgError((error && error.message) || '启动监控失败，请稍后重试')
      }
    },
    async stopMonitor(row) {
      try {
        const response = await stopDeviceMonitor(row.ape_id)
        const payload = response && response.data && typeof response.data === 'object' ? response.data : {}
        const hasSuccess = Object.prototype.hasOwnProperty.call(payload, 'success')
        const isFailed = hasSuccess && !payload.success
        const shortMessage = payload.shortMessage || (isFailed ? '停止监控失败，请稍后重试' : '已停止监控。')
        this.$message({
          type: isFailed ? 'warning' : 'success',
          message: shortMessage
        })
      } catch (error) {
        this.$modal.msgError((error && error.message) || '停止监控失败，请稍后重试')
      }
    },
    extractPreviewUrl(response) {
      if (!response) {
        return ''
      }
      const data = response.data || response
      return data.playUrl || data.previewUrl || data.url || data.streamUrl || data.rtspUrl || data.flvUrl || data.directSourceUrl || data.direct_source_url || data.liveUrl || data.live_url || ''
    },
    async handlePreview(row) {
      const apeId = row.ape_id || row.apeId || row.device_id || row.deviceId
      if (!apeId) {
        this.$modal.msgError('设备编码不存在，无法预览')
        return
      }
      const response = await previewDeviceMonitor(apeId)
      const playUrl = this.extractPreviewUrl(response)
      if (!playUrl) {
        this.$modal.msgWarning('暂无可播放地址，请先启动监控后重试')
        return
      }
      this.rtspUrl = playUrl
      this.viewProof = true
    },
    // ---- 云台控制（GB28181）----
    openPtzDialog(row) {
      const apeId = row.ape_id || row.apeId || row.device_id || row.deviceId
      this.ptzDevice = { ...row, apeId }
      this.ptzDeviceName = row.name || apeId || ''
      this.ptzGbId = row.gb_id || row.gbId || row.gb_device_id || ''
      this.ptzTitle = `云台控制 - ${this.ptzDeviceName}`
      this.ptzPreviewing = false
      this.ptzOpen = true
    },
    // 云台弹窗内预览：调 WVP 点播拿播放地址
    async ptzPreviewToggle() {
      const apeId = this.ptzDevice && (this.ptzDevice.apeId || this.ptzDevice.ape_id)
      if (!apeId) {
        this.$modal.msgError('设备编码不存在')
        return
      }
      if (this.ptzPreviewing) {
        this.ptzPreviewing = false
        return
      }
      try {
        const response = await previewDeviceMonitor(apeId)
        const playUrl = this.extractPreviewUrl(response)
        if (!playUrl) {
          this.$modal.msgWarning('暂无可播放地址，请先启动监控')
          return
        }
        this.rtspUrl = playUrl
        this.ptzPreviewing = true
        this.viewProof = true
      } catch (error) {
        this.$modal.msgError((error && error.message) || '预览失败')
      }
    },
    // 按住开始（连续发送），松开停止
    ptzPress(command) {
      if (this.ptzHoldTimer) {
        clearInterval(this.ptzHoldTimer)
      }
      this.ptzHoldCommand = command
      this.ptzSend(command)
      this.ptzHoldTimer = setInterval(() => {
        this.ptzSend(this.ptzHoldCommand)
      }, 600)
    },
    ptzRelease(command) {
      if (this.ptzHoldCommand !== command) {
        return
      }
      this.ptzStopHold()
      this.ptzSend('stop')
    },
    ptzStopHold() {
      if (this.ptzHoldTimer) {
        clearInterval(this.ptzHoldTimer)
        this.ptzHoldTimer = null
      }
      this.ptzHoldCommand = ''
    },
    async ptzSend(command) {
      const apeId = this.ptzDevice && (this.ptzDevice.apeId || this.ptzDevice.ape_id)
      if (!apeId) {
        return
      }
      try {
        await ptzControl(apeId, command)
      } catch (error) {
        this.$modal.msgError((error && error.message) || `云台控制(${command})失败`)
        this.ptzStopHold()
      }
    },
    async ptzHome() {
      const apeId = this.ptzDevice && (this.ptzDevice.apeId || this.ptzDevice.ape_id)
      if (!apeId) {
        return
      }
      try {
        await ptzHome(apeId)
        this.$modal.msgSuccess('云台归位已下发')
      } catch (error) {
        this.$modal.msgError((error && error.message) || '云台归位失败')
      }
    },
    async ptzPreset(action) {
      const apeId = this.ptzDevice && (this.ptzDevice.apeId || this.ptzDevice.ape_id)
      if (!apeId) {
        return
      }
      try {
        if (action === 'add') {
          await ptzPresetAdd(apeId, this.ptzPresetId)
          this.$modal.msgSuccess(`预置位 ${this.ptzPresetId} 已保存`)
        } else {
          await ptzPresetCall(apeId, this.ptzPresetId)
          this.$modal.msgSuccess(`正在转到预置位 ${this.ptzPresetId}`)
        }
      } catch (error) {
        this.$modal.msgError((error && error.message) || `预置位操作失败`)
      }
    },
    warningHistory(row) {
      this.device_id = row.ape_id || row.apeId || row.place
      this.deviceListShow = false
      this.warningTitle = `正在查看「${row.name}」的历史报警信息`
    }
  }
}
</script>

<style scoped>
::v-deep .operation-column .cell {
  white-space: nowrap;
}

::v-deep .operation-column .el-button + .el-button {
  margin-left: 6px;
}

::v-deep .operation-column .el-button--text {
  padding: 0;
}
</style>
