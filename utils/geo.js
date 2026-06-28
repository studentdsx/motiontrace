var MAX_LOCATION_ACCURACY = 120;

function toRadians(value) {
  return value * Math.PI / 180;
}

function distanceBetween(a, b) {
  if (!a || !b) return 0;
  var radius = 6371000;
  var lat1 = toRadians(a.latitude);
  var lat2 = toRadians(b.latitude);
  var deltaLat = toRadians(b.latitude - a.latitude);
  var deltaLon = toRadians(b.longitude - a.longitude);
  var sinLat = Math.sin(deltaLat / 2);
  var sinLon = Math.sin(deltaLon / 2);
  var h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
  return radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function normalizePoint(location) {
  return {
    latitude: location.latitude,
    longitude: location.longitude,
    accuracy: location.accuracy || 0,
    speed: location.speed || 0,
    timestamp: Date.now()
  };
}

function isUsablePoint(point) {
  return point &&
    typeof point.latitude === 'number' &&
    typeof point.longitude === 'number' &&
    (!point.accuracy || point.accuracy <= MAX_LOCATION_ACCURACY);
}

module.exports = {
  distanceBetween: distanceBetween,
  normalizePoint: normalizePoint,
  isUsablePoint: isUsablePoint
};
