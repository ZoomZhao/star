package com.starexplorer.app;

import static org.junit.Assert.*;

import com.starexplorer.app.nativeui.LayoutPolicy;
import org.junit.Test;

public class LayoutPolicyTest {

  @Test
  public void shortWindowsAndLargeFontsReducePageSize() {
    assertEquals(4, LayoutPolicy.tasksPerPage(1120, 688, 1));
    assertEquals(2, LayoutPolicy.tasksPerPage(1120, 480, 1));
    assertEquals(2, LayoutPolicy.tasksPerPage(1120, 688, 1.4f));
    assertEquals(2, LayoutPolicy.tasksPerPage(390, 780, 1));
  }

  @Test
  public void allTasksRemainReachableAndFilterClampsPage() {
    assertEquals(3, LayoutPolicy.pageCount(10, 4));
    assertEquals(0, LayoutPolicy.page(2, 2, 4));
    assertEquals(2, LayoutPolicy.page(99, 10, 4));
    assertEquals(0, LayoutPolicy.page(-1, 0, 4));
    assertEquals(1, LayoutPolicy.pageCount(0, 4));
  }
}
