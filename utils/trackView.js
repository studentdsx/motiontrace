var dateUtil = require('./date');

var TRACK_COLOR = '#1f6f54';
var CHECKIN_COLOR = '#d7654b';

function toMapPoint(point) {
  return {
    latitude: point.latitude,
    longitude: point.longitude
  };
}

function buildPolyline(points) {
  points = points || [];
  if (points.length <= 1) return [];
  return [{
    points: points.map(toMapPoint),
    color: TRACK_COLOR,
    width: 6,
    arrowLine: true
  }];
}

function buildMarkers(checkins) {
  return (checkins || []).map(function(item, index) {
    return {
      id: index + 1,
      latitude: item.latitude,
      longitude: item.longitude,
      callout: {
        content: item.note || '打卡',
        color: '#24302b',
        fontSize: 12,
        borderRadius: 4,
        bgColor: '#fffdf8',
        padding: 6,
        display: 'BYCLICK'
      },
      label: {
        content: String(index + 1),
        color: '#ffffff',
        fontSize: 12,
        bgColor: CHECKIN_COLOR,
        borderRadius: 12,
        padding: 6
      }
    };
  });
}

function formatStats(day, options) {
  options = options || {};
  var startTime = day.startTime || 0;
  var endTime = options.durationEnd || day.endTime || 0;
  return {
    distance: dateUtil.formatDistance(day.distanceMeters),
    duration: dateUtil.formatDuration(endTime - startTime),
    points: (day.points || []).length,
    checkins: (day.checkins || []).length
  };
}

function formatCheckins(checkins) {
  return (checkins || []).map(function(item) {
    return {
      id: item.id,
      time: dateUtil.formatTime(item.timestamp),
      note: item.note,
      photos: item.photos || []
    };
  });
}

function resolveCenter(day, fallback) {
  var points = day.points || [];
  var checkins = day.checkins || [];
  var source = points[points.length - 1] || checkins[0] || fallback;
  return {
    latitude: source.latitude,
    longitude: source.longitude
  };
}

module.exports = {
  buildMarkers: buildMarkers,
  buildPolyline: buildPolyline,
  formatCheckins: formatCheckins,
  formatStats: formatStats,
  resolveCenter: resolveCenter
};
