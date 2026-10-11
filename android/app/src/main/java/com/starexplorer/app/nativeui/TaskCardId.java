package com.starexplorer.app.nativeui;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A reusable task reference, never a login credential or an award amount. */
public final class TaskCardId {
  private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final Pattern FORMAT = Pattern.compile("star-task://v1/(" + UUID + ")/(" + UUID + ")");
  public final String childId;
  public final String ruleKey;

  public TaskCardId(String childId, String ruleKey) {
    if (childId == null || ruleKey == null || !childId.matches(UUID) || !ruleKey.matches(UUID))
      throw new IllegalArgumentException("任务卡编号无效");
    this.childId = childId;
    this.ruleKey = ruleKey;
  }

  public static TaskCardId parse(String value) {
    if (value == null || value.length() > 128) throw new IllegalArgumentException("这不是星星探险家的任务卡");
    Matcher match = FORMAT.matcher(value);
    if (!match.matches()) throw new IllegalArgumentException("这不是星星探险家的任务卡");
    return new TaskCardId(match.group(1), match.group(2));
  }

  public String toUri() { return "star-task://v1/" + childId + "/" + ruleKey; }

  @Override public boolean equals(Object other) {
    if (!(other instanceof TaskCardId)) return false;
    TaskCardId card = (TaskCardId) other;
    return childId.equals(card.childId) && ruleKey.equals(card.ruleKey);
  }

  @Override public int hashCode() { return Objects.hash(childId, ruleKey); }
}
