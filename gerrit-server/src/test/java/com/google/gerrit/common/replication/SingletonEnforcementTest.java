package com.google.gerrit.common.replication;

import com.wandisco.gerrit.gitms.shared.util.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.security.InvalidParameterException;

public class SingletonEnforcementTest {

  @Rule
  public ExpectedException exception = ExpectedException.none();

  @Before
  public void before(){
    SingletonEnforcement.setDisableEnforcement(false);
  }

  @AfterClass
  public static void afterClass(){
    // lets keep enforcement off in tests.
    SingletonEnforcement.setDisableEnforcement(true);
  }

  @Test
  public void testSingletonEnforcement(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");

    SingletonEnforcement.registerClass(uniqueName);

    exception.expect(InvalidParameterException.class);

    // now we should not be able to call it again without error
    SingletonEnforcement.registerClass(uniqueName);
  }

  @Test
  public void testSingletonEnforcementUnregister(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");


    SingletonEnforcement.registerClass(uniqueName);
    SingletonEnforcement.unregisterClass(uniqueName);
    SingletonEnforcement.registerClass(uniqueName);
  }

  @Test
  public void testSingletonEnforcementDisable(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");


    SingletonEnforcement.registerClass(uniqueName);
    SingletonEnforcement.setDisableEnforcement(true);
    SingletonEnforcement.registerClass(uniqueName);
  }


  @Test
  public void testSingletonEnforcementClear(){
    String uniqueName = StringUtils.createUniqueString("SomeTestclass");

    SingletonEnforcement.registerClass(uniqueName);
    SingletonEnforcement.clearAll();
    SingletonEnforcement.registerClass(uniqueName);
  }
}
