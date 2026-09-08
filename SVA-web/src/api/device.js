import request from '@/utils/request'

export function getDeviceList(query) {
  return request({
    url: `/waring/device/list`,
    method: 'get',
    params: query
  });
}

export function getDevice(apeId) {
  return request({
    url: `/waring/device/${apeId}`,
    method: 'get'
  });
}

export function addDevice(data) {
  return request({
    url: `/waring/device`,
    method: 'post',
    data: data
  });
}

export function updateDevice(data) {
  return request({
    url: `/waring/device`,
    method: 'put',
    data: data
  });
}

export function delDevice(apeIdOrIds) {
  return request({
    url: `/waring/device/${apeIdOrIds}`,
    method: 'delete'
  });
}

export function getLDeviceList(query) {
  return request({
    url: `/waring/device/lixian`,
    method: 'get',
    params: query
  });
}

export function getHistoryWaring(data) {
  return request({
    url: `/waring/waring/getHistoryWaring`,
    method: 'get',
    params: data
  });
}

export async function getMonitorUrl(device_id, token) {
  const url = `http://192.168.101.174:11125/api/vms/v2/webuas/live/stream/url?channel_code=${device_id}&stream_type=0&stream_mode=1&keep_alive=100&visit_ip=192.168.136.246`

  const response = await fetch(url, {
    method: 'GET',
    headers: {
      'Authorization': token,
      'User': 'usercode:SYCC',
      'Cookie': 'usercode=SYCC',
      'Content-Type': 'application/json'
    }
  });
  const data = await response.json();
  return data;
}

export function getDirectLiveUrl(apeId) {
  return request({
    url: `/waring/device/live/direct/${apeId}`,
    method: 'get'
  });
}

export function startDeviceMonitor(apeId) {
  return request({
    url: `/waring/device/monitor/${apeId}/start`,
    method: 'post',
    timeout: 23000
  })
}

export function stopDeviceMonitor(apeId) {
  return request({
    url: `/waring/device/monitor/${apeId}/stop`,
    method: 'post',
    timeout: 23000
  })
}

export function previewDeviceMonitor(apeId) {
  return request({
    url: `/waring/device/monitor/${apeId}/preview`,
    method: 'get'
  })
}

// 云台控制（GB28181 摄像头转动/变焦）：后端 /waring/device/ptz/{apeId}
export function ptzControl(apeId, command, speeds) {
  const params = { command }
  if (speeds) {
    if (speeds.horizonSpeed !== undefined) params.horizonSpeed = speeds.horizonSpeed
    if (speeds.verticalSpeed !== undefined) params.verticalSpeed = speeds.verticalSpeed
    if (speeds.zoomSpeed !== undefined) params.zoomSpeed = speeds.zoomSpeed
  }
  return request({
    url: `/waring/device/ptz/${apeId}`,
    method: 'post',
    params,
    timeout: 30000
  })
}

// 云台归位（回中）
export function ptzHome(apeId) {
  return request({
    url: `/waring/device/ptz/${apeId}/home`,
    method: 'post',
    timeout: 30000
  })
}

// 预置位：调用 / 设置
export function ptzPresetCall(apeId, presetId) {
  return request({
    url: `/waring/device/ptz/${apeId}/preset/${presetId}/call`,
    method: 'post',
    timeout: 30000
  })
}
export function ptzPresetAdd(apeId, presetId) {
  return request({
    url: `/waring/device/ptz/${apeId}/preset/${presetId}/add`,
    method: 'post',
    timeout: 30000
  })
}

// ---- GB28181 国标扩展（2026-09-07） ----
// 手动触发一次"从 WVP 同步国标设备"
export function syncGbDevices() {
  return request({
    url: `/waring/device/gb/sync`,
    method: 'post',
    timeout: 30000
  })
}

// WVP 国标平台连接配置 CRUD（后端 /waring/wvp）
export function listWvpServer(query) {
  return request({
    url: `/waring/wvp/list`,
    method: 'get',
    params: query
  })
}

export function getWvpServer(id) {
  return request({
    url: `/waring/wvp/${id}`,
    method: 'get'
  })
}

export function addWvpServer(data) {
  return request({
    url: `/waring/wvp`,
    method: 'post',
    data: data
  })
}

export function updateWvpServer(data) {
  return request({
    url: `/waring/wvp`,
    method: 'put',
    data: data
  })
}

export function delWvpServer(id) {
  return request({
    url: `/waring/wvp/${id}`,
    method: 'delete'
  })
}

// 把某平台一键设为所有国标设备的归属平台（写 h_device.wvp_server_id）
export function applyWvpToDevices(id) {
  return request({
    url: `/waring/wvp/apply/${id}`,
    method: 'post'
  })
}

// 国标设备登记 / 一键模拟器（后端 /waring/device/gb）
export function registerGbDevice(data) {
  return request({
    url: `/waring/device/gb/register`,
    method: 'post',
    data: data,
    timeout: 30000
  })
}

export function gbSimStart(data) {
  return request({
    url: `/waring/device/gb/sim/start`,
    method: 'post',
    data: data,
    timeout: 40000
  })
}

export function gbSimStop(data) {
  return request({
    url: `/waring/device/gb/sim/stop`,
    method: 'post',
    data: data
  })
}

export function gbSimList() {
  return request({
    url: `/waring/device/gb/sim/list`,
    method: 'get'
  })
}
