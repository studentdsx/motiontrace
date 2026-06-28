var dateUtil = require('../../utils/date');
var trackStore = require('../../utils/trackStore');
var trackView = require('../../utils/trackView');

Page({
  data: {
    date: '',
    weekLabel: '',
    scale: 15,
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
    checkins: []
  },

  onLoad: function(options) {
    var date = options.date || dateUtil.formatDate();
    this.setData({
      date: date,
      weekLabel: dateUtil.formatWeek(date)
    });
    this.loadDay(date);
  },

  loadDay: function(date) {
    var day = trackStore.getDay(date);
    var points = day.points || [];
    var checkins = day.checkins || [];

    this.setData({
      center: trackView.resolveCenter(day, this.data.center),
      polyline: trackView.buildPolyline(points),
      markers: trackView.buildMarkers(checkins),
      stats: trackView.formatStats(day),
      checkins: trackView.formatCheckins(checkins)
    });
  },

  previewPhoto: function(event) {
    wx.previewImage({
      current: event.currentTarget.dataset.src,
      urls: event.currentTarget.dataset.photos
    });
  }
});
