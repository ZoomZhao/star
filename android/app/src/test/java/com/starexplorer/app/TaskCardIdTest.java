package com.starexplorer.app;

import static org.junit.Assert.*;
import com.starexplorer.app.nativeui.TaskCardId;
import org.junit.Test;

public class TaskCardIdTest {
  private static final String CHILD = "00000000-1111-2222-3333-444444444444";
  private static final String RULE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

  @Test public void reusableReferenceContainsNoMutableRuleOrAwardData() {
    TaskCardId card = new TaskCardId(CHILD, RULE);
    assertEquals(card, TaskCardId.parse(card.toUri()));
    assertEquals(CHILD, TaskCardId.parse(card.toUri()).childId);
    assertEquals(RULE, TaskCardId.parse(card.toUri()).ruleKey);
    assertTrue(card.toUri().length() < 128);
  }

  @Test public void malformedForeignAndFutureVersionCardsAreRejected() {
    String valid = new TaskCardId(CHILD, RULE).toUri();
    for (String invalid : new String[] {null, "", "https://example.com", valid + "?stars=100", valid + "/", valid.replace("v1", "v2"), valid.replace(CHILD, "../admin"), valid.replace(RULE, "wrong")}) {
      assertThrows(IllegalArgumentException.class, () -> TaskCardId.parse(invalid));
    }
  }
}
