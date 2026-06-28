package com.motiontrace.diary;

final class GeoUtils {
    private GeoUtils() {
    }

    static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double radius = 6371000.0;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double sinPhi = Math.sin(dPhi / 2.0);
        double sinLambda = Math.sin(dLambda / 2.0);
        double h = sinPhi * sinPhi + Math.cos(phi1) * Math.cos(phi2) * sinLambda * sinLambda;
        return radius * 2.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1.0 - h));
    }
}
