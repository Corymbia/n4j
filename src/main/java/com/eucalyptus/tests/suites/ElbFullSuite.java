package com.eucalyptus.tests.suites;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;
import com.eucalyptus.tests.awssdk.*;

@RunWith(Suite.class)
@SuiteClasses({
    // suites
    ElbShortSuite.class,

    // tests
    TestELBEC2Instance.class,
})
public class ElbFullSuite {
  // junit test suite as defined by SuiteClasses annotation
}
