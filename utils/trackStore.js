var dateUtil = require('./date');
var geo = require('./geo');

var STORAGE_KEY = 'motion_tracks_v1';
var MIN_POINT_DISTANCE = 3;
var MAX_SEGMENT_DISTANCE = 1000;

function readAll() {
  return wx.getStorageSync(STORAGE_KEY) || {};
}

function writeAll(store) {
  wx.setStorageSync(STORAGE_KEY, store || {});
}

function createDay(date) {
  return {
    date: date,
    points: [],
    checkins: [],
    distanceMeters: 0,
    startTime: 0,
    endTime: 0,
    updatedAt: Date.now()
  };
}

function getDay(date) {
  var targetDate = date || dateUtil.formatDate();
  var store = readAll();
  return store[targetDate] || createDay(targetDate);
}

function saveDay(day) {
  var store = readAll();
  day.updatedAt = Date.now();
  store[day.date] = day;
  writeAll(store);
  return day;
}

function appendPoint(date, point) {
  if (!geo.isUsablePoint(point)) return getDay(date);
  var day = getDay(date);
  var last = day.points[day.points.length - 1];
  var gap = geo.distanceBetween(last, point);

  if (last && gap < MIN_POINT_DISTANCE) {
    day.endTime = point.timestamp;
    return saveDay(day);
  }

  if (last && gap < MAX_SEGMENT_DISTANCE) {
    day.distanceMeters += gap;
  }

  if (!day.startTime) day.startTime = point.timestamp;
  day.endTime = point.timestamp;
  day.points.push(point);
  return saveDay(day);
}

function addCheckin(date, checkin) {
  var day = getDay(date);
  day.checkins.unshift(checkin);
  if (!day.startTime) day.startTime = checkin.timestamp;
  day.endTime = Math.max(day.endTime || 0, checkin.timestamp);
  return saveDay(day);
}

function listDays() {
  var store = readAll();
  return Object.keys(store)
    .map(function(date) {
      return store[date];
    })
    .sort(function(a, b) {
      return b.date.localeCompare(a.date);
    });
}

function clearAll() {
  wx.removeStorageSync(STORAGE_KEY);
}

module.exports = {
  appendPoint: appendPoint,
  addCheckin: addCheckin,
  clearAll: clearAll,
  getDay: getDay,
  listDays: listDays,
  saveDay: saveDay
};
