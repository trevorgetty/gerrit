package com.google.gerrit.common.replication;

import com.google.common.base.Strings;


import static com.google.gerrit.common.replication.ReplicationConstants.REPLICATION_DISABLED;

import com.google.inject.Singleton;
import org.eclipse.jgit.lib.Config;

@Singleton //Not guice bound but makes it clear that its a singleton
public final class ConfigureReplication {

  // A flag, which allow there to be no application properties and for us to behave like a normal vanilla non replicated environment.
  private Boolean replicationDisabled = null;
  private Config config;

  private static ConfigureReplication INSTANCE;

  private ConfigureReplication(Config config) {
    this.config = config;
  }

  //Get singleton instance
  public static ConfigureReplication getInstance(Config config) {
    if(INSTANCE == null) {
      INSTANCE = new ConfigureReplication(config);
      SingletonEnforcement.registerClass(ConfigureReplication.class);
    }

    return INSTANCE;
  }

  /**
   * Method to inverse the isReplicationDisabled method, to allow it to be easily
   * supplied to implementations which expected it to be the isreplicated flag, allowing us to
   * control the turning off of replication more easily.
   *
   * @return true if replication is ENABLED
   */
  public boolean isReplicationEnabled() {
    return !isReplicationDisabled();
  }

  /**
   * A specific version of getReplicationDisabled which reads the value from GerritServerConfig,
   * NOT our replicated configuration. Used by integration tests.
   *
   * @return returns true is GerritMs replication should be disabled, used only by tests.
   */
  public boolean getReplicationDisabledServerConfig() {
    return config.getBoolean("wandisco", null, "gerritmsReplicationDisabled", false);
  }


  /**
   * A very core configuration override now which allows the full replication element
   * of GerritMS to be disabled and essentially for it to return to default vanilla behaviour.
   *
   * @return true if replication is DISABLED
   */
  public boolean isReplicationDisabled() {

    if (replicationDisabled == null) {
      replicationDisabled = getOverrideBehaviour(REPLICATION_DISABLED);
    }
    //If no system env found OR gerrit server config property set then return false.
    return replicationDisabled || getReplicationDisabledServerConfig();
  }


  /**
   * Utility method to get the system override properties and returns them as a boolean
   * indicating whether they are enabled / disabled.
   *
   * @param overrideName : Name of the flag to check for in the system env or properties
   *                     if replicationDisabled is null.
   * @return boolean value of the system env or property val.
   */
  private boolean getOverrideBehaviour(String overrideName) {

    // work out system env value first... Note as env is case sensitive and properties usually lower case, we will
    // use what the client has passed in, but also request toUpper for the environment option JIC.
    // e.g. 'replication_disabled' the property would be 'REPLICATION_DISABLED' the environment var.
    String env = System.getenv(overrideName);
    if ( Strings.isNullOrEmpty(env)){
      // retry with uppercase
      env = System.getenv(overrideName.toUpperCase());
    }
    return Boolean.parseBoolean(System.getProperty(overrideName, env));
  }
}
