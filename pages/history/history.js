var dateUtil = require('../../utils/date');
var trackStore = require('../../utils/trackStore');
var trackView = require('../../utils/trackView');

Page({
  data: {
    days: []
  },

  onShow: function() {
    this.syncTabBar();
    this.loadDays();
  },

  onPullDownRefresh: function() {
    this.loadDays();
    wx.stopPullDownRefresh();
  },

  syncTabBar: function() {
    if (this.getTabBar && this.getTabBar()) {
      this.getTabBar().setSelected(1);
    }
  },

  loadDays: function() {
    var days = trackStore.listDays().map(function(day) {
      var stats = trackView.formatStats(day);
      return {
        date: day.date,
        week: dateUtil.formatWeek(day.date),
        distance: stats.distance,
        duration: stats.duration,
        points: stats.points,
        checkins: stats.checkins
      };
    });
    this.setData({ days: days });
  },

  openDay: function(event) {
    wx.navigateTo({
      url: '/pages/day/day?date=' + event.currentTarget.dataset.date
    });
  }
});
