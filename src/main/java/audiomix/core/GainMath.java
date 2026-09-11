package audiomix.core;

public final class GainMath {
    private GainMath() {}

    public static double dbToLinear(double db) {
        return Math.pow(10, db / 20.0);
    }

    public static double linearToDb(double lin) {
        return lin <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(lin);
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static double[] equalPowerPan(double x) {
        x = clamp(x, -1.0, 1.0);
        double theta = (x + 1.0) * Math.PI / 4.0;
        return new double[] { Math.cos(theta), Math.sin(theta) };
    }
}
