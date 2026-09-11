package audiomix.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GainMathTest {

    @Test
    void dbToLinear() {
        assertEquals(1.0, GainMath.dbToLinear(0), 1e-9);
        assertEquals(0.5, GainMath.dbToLinear(GainMath.linearToDb(0.5)), 1e-9);
        assertEquals(2.0, GainMath.dbToLinear(GainMath.linearToDb(2.0)), 1e-9);
    }

    @Test
    void linearToDb() {
        assertEquals(0.0, GainMath.linearToDb(1.0), 1e-9);
        assertEquals(-6.020599913279624, GainMath.linearToDb(0.5), 1e-9);
        assertEquals(Double.NEGATIVE_INFINITY, GainMath.linearToDb(0.0));
    }

    @Test
    void equalPowerPan() {
        double[] center = GainMath.equalPowerPan(0);
        double sq2 = Math.sqrt(2) / 2.0;
        assertEquals(sq2, center[0], 1e-9);
        assertEquals(sq2, center[1], 1e-9);

        assertArrayEquals(new double[] { 1.0, 0.0 }, GainMath.equalPowerPan(-1), 1e-9);
        assertArrayEquals(new double[] { 0.0, 1.0 }, GainMath.equalPowerPan(1), 1e-9);
    }

    @Test
    void equalPowerPanClamps() {
        double[] oob = GainMath.equalPowerPan(5);
        assertEquals(0.0, oob[0], 1e-9);
        assertEquals(1.0, oob[1], 1e-9);
    }

    @Test
    void powerInvariance() {
        for (double x = -1; x <= 1; x += 0.25) {
            double[] lr = GainMath.equalPowerPan(x);
            double power = lr[0] * lr[0] + lr[1] * lr[1];
            assertEquals(1.0, power, 1e-12, "power != 1 at x=" + x);
        }
    }
}
