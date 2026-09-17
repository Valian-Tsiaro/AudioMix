package audiomix.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DelayLineTest {

    @Test
    void readRawReturnsValueKSamplesAgo() {
        DelayLine line = new DelayLine(10);
        for (int i = 1; i <= 5; i++) line.write((float) i);
        // readRaw(k) = value written k steps before the write cursor.
        // After writing 1..5, cursor is past 5. readRaw(1)=5, readRaw(2)=4, etc.
        assertEquals(5.0f, line.readRaw(1));
        assertEquals(4.0f, line.readRaw(2));
        assertEquals(3.0f, line.readRaw(3));
        assertEquals(1.0f, line.readRaw(5));
    }

    @Test
    void readInterpolatedHalfway() {
        DelayLine line = new DelayLine(20);
        for (int i = 1; i <= 15; i++) line.write((float) i);
        // readRaw(1)=15, readRaw(2)=14, ..., readRaw(6)=10, readRaw(7)=9
        // readInterpolated(6.5) = readRaw(6)*0.5 + readRaw(7)*0.5 = 9.5
        assertEquals(9.5f, line.readInterpolated(6.5), 1e-7f);
    }

    @Test
    void readInterpolatedExact() {
        DelayLine line = new DelayLine(10);
        line.write(7.0f);
        // readRaw(1) = value just written
        assertEquals(7.0f, line.readInterpolated(1.0), 0.0f);
    }

    @Test
    void readInterpolatedAtCapacity() {
        DelayLine line = new DelayLine(10);
        // Write 0..10 (11 values into a size-11 buffer). After full cycle pos=0.
        for (int i = 0; i <= 10; i++) line.write((float) i);
        // readRaw(10) = buf[(0-10+11)%11] = buf[1] = 1.0
        // (the value at buf[0]=0.0 is at distance 11 = size, beyond capacity)
        assertEquals(1.0f, line.readInterpolated(10.0), 0.0f);
    }

    @Test
    void resetZerosBuffer() {
        DelayLine line = new DelayLine(10);
        for (int i = 0; i < 5; i++) line.write(1.0f);
        line.reset();
        for (int k = 1; k <= line.capacity(); k++) {
            assertEquals(0.0f, line.readRaw(k));
        }
    }

    @Test
    void capacityTests() {
        DelayLine line = new DelayLine(10);
        assertEquals(10, line.capacity());
    }

    @Test
    void tooSmallThrows() {
        assertThrows(IllegalArgumentException.class, () -> new DelayLine(0));
        assertThrows(IllegalArgumentException.class, () -> new DelayLine(-1));
    }

    @Test
    void readRawZeroThrows() {
        DelayLine line = new DelayLine(10);
        line.write(1.0f);
        assertThrows(IllegalArgumentException.class, () -> line.readRaw(0));
    }

    @Test
    void nanThrows() {
        DelayLine line = new DelayLine(10);
        line.write(1.0f);
        assertThrows(IllegalArgumentException.class, () -> line.readInterpolated(Double.NaN));
    }

    @Test
    void belowOneThrows() {
        DelayLine line = new DelayLine(10);
        line.write(1.0f);
        assertThrows(IllegalArgumentException.class, () -> line.readInterpolated(0.5));
        assertThrows(IllegalArgumentException.class, () -> line.readInterpolated(0.0));
    }

    @Test
    void beyondCapacityThrows() {
        DelayLine line = new DelayLine(10);
        line.write(1.0f);
        assertThrows(IllegalArgumentException.class, () -> line.readRaw(11));
        assertThrows(IllegalArgumentException.class, () -> line.readInterpolated(11.0));
    }

    @Test
    void wrapAround() {
        DelayLine line = new DelayLine(3);
        line.write(1.0f);
        line.write(2.0f);
        line.write(3.0f);
        line.write(4.0f); // wraps, overwrites slot 0
        assertEquals(4.0f, line.readRaw(1));
        assertEquals(3.0f, line.readRaw(2));
        assertEquals(2.0f, line.readRaw(3));
    }
}
