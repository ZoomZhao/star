package com.starexplorer.app;

import static org.junit.Assert.*;

import com.starexplorer.app.nativeui.ShanghaiDate;
import java.util.TimeZone;
import org.junit.Test;

public class ShanghaiDateTest {
  @Test public void calendarArithmeticIsIndependentOfTheDeviceTimezoneAndDst() {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : new String[] {"Asia/Shanghai", "America/New_York", "UTC"}) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        assertEquals("2026-03-09", ShanghaiDate.shift("2026-03-08", 1));
        assertEquals("2026-11-01", ShanghaiDate.shift("2026-11-02", -1));
        assertEquals("2024-02-29", ShanghaiDate.shift("2024-02-28", 1));
        assertEquals("2024-03-01", ShanghaiDate.shift("2024-02-29", 1));
        assertEquals("2027-01-01", ShanghaiDate.shift("2026-12-31", 1));
      }
    } finally { TimeZone.setDefault(original); }
  }

  @Test public void invalidCalendarDatesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> ShanghaiDate.shift("2026-02-29", 1));
  }
}
