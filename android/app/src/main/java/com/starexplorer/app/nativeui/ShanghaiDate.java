package com.starexplorer.app.nativeui;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Calendar dates in the same business timezone as the API, independent of device settings. */
public final class ShanghaiDate {
  private ShanghaiDate() {}

  private static SimpleDateFormat format() {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
    format.setLenient(false);
    return format;
  }

  public static String today() { return format().format(new Date()); }

  public static String shift(String date, int days) {
    SimpleDateFormat format = format();
    try {
      Calendar calendar = Calendar.getInstance(format.getTimeZone());
      calendar.setTime(format.parse(date));
      calendar.add(Calendar.DATE, days);
      return format.format(calendar.getTime());
    } catch (ParseException e) {
      throw new IllegalArgumentException("日期无效", e);
    }
  }
}
