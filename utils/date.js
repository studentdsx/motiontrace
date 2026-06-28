function pad(value) {
  return value < 10 ? '0' + value : String(value);
}

function formatDate(date) {
  var target = date ? new Date(date) : new Date();
  return target.getFullYear() + '-' + pad(target.getMonth() + 1) + '-' + pad(target.getDate());
}

function formatTime(timestamp) {
  if (!timestamp) return '--:--';
  var target = new Date(timestamp);
  return pad(target.getHours()) + ':' + pad(target.getMinutes());
}

function formatWeek(dateString) {
  var weeks = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];
  return weeks[new Date(dateString.replace(/-/g, '/')).getDay()];
}

function formatDuration(ms) {
  if (!ms || ms < 0) return '0分';
  var minutes = Math.floor(ms / 60000);
  var hours = Math.floor(minutes / 60);
  var rest = minutes % 60;
  if (hours > 0) return hours + '时' + rest + '分';
  return rest + '分';
}

function formatDistance(meters) {
  if (!meters) return '0.00 km';
  return (meters / 1000).toFixed(2) + ' km';
}

module.exports = {
  formatDate: formatDate,
  formatTime: formatTime,
  formatWeek: formatWeek,
  formatDuration: formatDuration,
  formatDistance: formatDistance
};
