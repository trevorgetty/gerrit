package com.google.gerrit.common;

/**
 * This is a wrapper for the cache method call to be replicated,
 * so that it's easier to rebuild the "original" on the landing
 * node.
 *
 */
public class CacheObjectCallWrapper extends CacheKeyWrapper {
  public String methodName;

  public CacheObjectCallWrapper(String cacheName, String method, Object key) {
    super(cacheName,key);
    this.methodName = method;
  }

  @Override
  public String toString() {
    return "CacheObjectCallWrapper{" + "methodName=" + methodName + "[ "+ super.toString() +" ]" + '}';
  }

}
