var dateUtil = require('../../utils/date');
var geo = require('../../utils/geo');
var trackStore = require('../../utils/trackStore');
var trackView = require('../../utils/trackView');

var FALLBACK_SAMPLE_INTERVAL = 60000;
var RENDER_THROTTLE_INTERVAL = 3000;
var STATS_REFRESH_INTERVAL = 30000;

Page({
  data: {
    date: '',
    dateLabel: '',
    weekLabel: '',
    isRecording: false,
    scale: 16,
    center: {
      latitude: 31.2304,
      longitude: 121.4737
    },
    polyline: [],
    markers: [],
    stats: {
      distance: '0.00 km',
      duration: '0分',
      points: 0,
      checkins: 0
    },
    checkins: [],
    checkinSheet: false,
    pendingCheckin: null,
    pendingLocationText: '',
    selectedPhotos: [],
    note: '',
    maxPhotos: 9,
    savingCheckin: false
  },

  onLoad: function() {
    this.locationHandler = this.handleLocationChange.bind(this);
    this.statsTimer = null;
    this.sampleTimer = null;
    this.pendingRenderTimer = null;
    this.pendingRenderDay = null;
    this.pendingRenderOptions = null;
    this.lastRenderAt = 0;
    this.startingRecording = false;
    this.bootstrapToday();
  },

  onShow: function() {
    this.syncTabBar();
    this.refreshToday();
  },

  onUnload: function() {
    this.stopRecording();
    this.clearPendingRender();
  },

  onPullDownRefresh: function() {
    this.refreshToday();
    wx.stopPullDownRefresh();
  },

  noop: function() {},

  syncTabBar: function() {
    if (this.getTabBar && this.getTabBar()) {
      this.getTabBar().setSelected(0);
    }
  },

  bootstrapToday: function() {
    var today = dateUtil.formatDate();
    this.setData({
      date: today,
      dateLabel: today,
      weekLabel: dateUtil.formatWeek(today)
    });
    this.refreshToday();
    this.centerOnCurrentLocationIfAuthed();
  },

  centerOnCurrentLocationIfAuthed: function() {
    wx.getSetting({
      success: function(res) {
        if (!res.authSetting['scope.userLocation']) return;
        this.getCurrentLocation(function(point) {
          this.setData({
            center: {
              latitude: point.latitude,
              longitude: point.longitude
            }
          });
        }.bind(this), true);
      }.bind(this)
    });
  },

  refreshToday: function(day, options) {
    this.renderDay(day || trackStore.getDay(this.data.date || dateUtil.formatDate()), options);
  },

  renderDay: function(day, options) {
    options = options || {};
    var points = day.points || [];
    var checkins = day.checkins || [];
    var durationEnd = this.data.isRecording && day.startTime ? Date.now() : day.endTime;

    var nextData = {
      polyline: trackView.buildPolyline(points),
      markers: trackView.buildMarkers(checkins),
      stats: trackView.formatStats(day, { durationEnd: durationEnd }),
      checkins: trackView.formatCheckins(checkins)
    };

    if (options.center && (points.length || checkins.length)) {
      nextData.center = trackView.resolveCenter(day, this.data.center);
    }

    this.lastRenderAt = Date.now();
    this.setData(nextData);
  },

  scheduleRenderDay: function(day, options) {
    var now = Date.now();
    var elapsed = now - this.lastRenderAt;
    this.pendingRenderDay = day;
    this.pendingRenderOptions = options || this.pendingRenderOptions;

    if (elapsed >= RENDER_THROTTLE_INTERVAL) {
      this.clearPendingRender();
      this.renderDay(day, options);
      return;
    }

    if (this.pendingRenderTimer) return;
    this.pendingRenderTimer = setTimeout(function() {
      var pendingDay = this.pendingRenderDay;
      var pendingOptions = this.pendingRenderOptions;
      this.pendingRenderTimer = null;
      this.pendingRenderDay = null;
      this.pendingRenderOptions = null;
      if (pendingDay) {
        this.renderDay(pendingDay, pendingOptions);
      }
    }.bind(this), RENDER_THROTTLE_INTERVAL - elapsed);
  },

  clearPendingRender: function() {
    if (this.pendingRenderTimer) {
      clearTimeout(this.pendingRenderTimer);
      this.pendingRenderTimer = null;
    }
    this.pendingRenderDay = null;
    this.pendingRenderOptions = null;
  },

  onRecordSwitch: function(event) {
    if (event.detail.value) {
      this.startRecording();
    } else {
      this.stopRecording();
    }
  },

  startRecording: function() {
    if (this.data.isRecording || this.startingRecording) return;
    this.startingRecording = true;
    this.ensureLocationPermission(function() {
      wx.startLocationUpdate({
        type: 'gcj02',
        success: function() {
          wx.onLocationChange(this.locationHandler);
          this.startingRecording = false;
          this.setData({ isRecording: true });
          this.startStatsTimer();
          this.startSampleTimer();
          this.recordCurrentLocation(true);
          wx.showToast({ title: '开始记录', icon: 'success' });
        }.bind(this),
        fail: function() {
          this.startingRecording = false;
          this.setData({ isRecording: false });
          wx.showToast({ title: '定位启动失败', icon: 'none' });
        }.bind(this)
      });
    }.bind(this));
  },

  stopRecording: function() {
    this.startingRecording = false;
    if (this.locationHandler) {
      wx.offLocationChange(this.locationHandler);
    }
    wx.stopLocationUpdate();
    this.stopStatsTimer();
    this.stopSampleTimer();
    if (this.data.isRecording) {
      wx.showToast({ title: '已停止记录', icon: 'none' });
    }
    this.setData({ isRecording: false });
    this.refreshToday();
  },

  startStatsTimer: function() {
    this.stopStatsTimer();
    this.statsTimer = setInterval(function() {
      this.refreshToday();
    }.bind(this), STATS_REFRESH_INTERVAL);
  },

  stopStatsTimer: function() {
    if (this.statsTimer) {
      clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
  },

  startSampleTimer: function() {
    this.stopSampleTimer();
    this.sampleTimer = setInterval(function() {
      if (this.data.isRecording) {
        this.recordCurrentLocation();
      }
    }.bind(this), FALLBACK_SAMPLE_INTERVAL);
  },

  stopSampleTimer: function() {
    if (this.sampleTimer) {
      clearInterval(this.sampleTimer);
      this.sampleTimer = null;
    }
  },

  ensureLocationPermission: function(callback) {
    wx.getSetting({
      success: function(res) {
        var authed = res.authSetting['scope.userLocation'];
        if (authed) {
          callback();
          return;
        }
        if (authed === false) {
          this.showPermissionDialog();
          return;
        }
        wx.authorize({
          scope: 'scope.userLocation',
          success: callback,
          fail: this.showPermissionDialog.bind(this)
        });
      }.bind(this),
      fail: this.showPermissionDialog.bind(this)
    });
  },

  showPermissionDialog: function() {
    this.startingRecording = false;
    wx.showModal({
      title: '需要定位权限',
      content: '开启定位后才能记录运动轨迹和保存打卡位置。',
      confirmText: '去设置',
      success: function(res) {
        if (res.confirm) wx.openSetting();
      }
    });
    this.setData({ isRecording: false });
  },

  getCurrentLocation: function(callback, quiet) {
    wx.getLocation({
      type: 'gcj02',
      success: function(res) {
        var point = geo.normalizePoint(res);
        if (geo.isUsablePoint(point)) callback(point);
      },
      fail: function() {
        if (!quiet) wx.showToast({ title: '无法获取当前位置', icon: 'none' });
      }
    });
  },

  recordCurrentLocation: function(centerAfter) {
    this.getCurrentLocation(function(point) {
      var day = trackStore.appendPoint(this.data.date, point);
      this.scheduleRenderDay(day, centerAfter ? { center: true } : null);
    }.bind(this));
  },

  handleLocationChange: function(res) {
    var point = geo.normalizePoint(res);
    var day = trackStore.appendPoint(this.data.date, point);
    this.scheduleRenderDay(day);
  },

  centerOnMe: function() {
    this.ensureLocationPermission(function() {
      this.getCurrentLocation(function(point) {
        this.setData({
          center: {
            latitude: point.latitude,
            longitude: point.longitude
          }
        });
      }.bind(this));
    }.bind(this));
  },

  startCheckin: function() {
    this.ensureLocationPermission(function() {
      this.getCurrentLocation(function(point) {
        this.setData({
          pendingCheckin: point,
          pendingLocationText: point.latitude.toFixed(5) + ', ' + point.longitude.toFixed(5),
          selectedPhotos: [],
          note: '',
          savingCheckin: false,
          checkinSheet: true
        });
      }.bind(this));
    }.bind(this));
  },

  chooseCheckinPhotos: function() {
    var remaining = this.data.maxPhotos - this.data.selectedPhotos.length;
    if (remaining <= 0) {
      wx.showToast({ title: '最多添加9张照片', icon: 'none' });
      return;
    }
    wx.chooseMedia({
      count: remaining,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: function(res) {
        var paths = (res.tempFiles || []).map(function(file) {
          return file.tempFilePath;
        });
        this.savePhotos(paths, function(savedPhotos) {
          this.setData({
            selectedPhotos: this.data.selectedPhotos.concat(savedPhotos).slice(0, this.data.maxPhotos)
          });
        }.bind(this));
      }.bind(this)
    });
  },

  removeSelectedPhoto: function(event) {
    var index = event.currentTarget.dataset.index;
    var photos = this.data.selectedPhotos.slice();
    photos.splice(index, 1);
    this.setData({ selectedPhotos: photos });
  },

  savePhotos: function(paths, callback) {
    if (!paths.length) {
      callback([]);
      return;
    }
    var saved = [];
    var remaining = paths.length;
    paths.forEach(function(path) {
      wx.saveFile({
        tempFilePath: path,
        success: function(res) {
          saved.push(res.savedFilePath);
        },
        fail: function() {
          saved.push(path);
        },
        complete: function() {
          remaining -= 1;
          if (remaining === 0) callback(saved);
        }
      });
    });
  },

  onNoteInput: function(event) {
    this.setData({ note: event.detail.value });
  },

  cancelCheckin: function() {
    this.setData({
      checkinSheet: false,
      pendingCheckin: null,
      selectedPhotos: [],
      note: '',
      savingCheckin: false
    });
  },

  confirmCheckin: function() {
    if (this.data.savingCheckin) return;
    var point = this.data.pendingCheckin;
    if (!point) return;
    this.setData({ savingCheckin: true });
    var checkin = {
      id: 'checkin_' + Date.now(),
      latitude: point.latitude,
      longitude: point.longitude,
      timestamp: Date.now(),
      note: (this.data.note || '').trim(),
      photos: this.data.selectedPhotos
    };
    var day = trackStore.addCheckin(this.data.date, checkin);
    this.cancelCheckin();
    this.renderDay(day);
    wx.showToast({ title: '已保存打卡', icon: 'success' });
  },

  previewPhoto: function(event) {
    wx.previewImage({
      current: event.currentTarget.dataset.src,
      urls: event.currentTarget.dataset.photos
    });
  }
});
