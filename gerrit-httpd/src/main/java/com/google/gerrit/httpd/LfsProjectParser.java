
/********************************************************************************
 * Copyright (c) 2014-2018 WANdisco
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Apache License, Version 2.0
 *
 ********************************************************************************/
 
package com.google.gerrit.httpd;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LfsProjectParser {

  public static final String LFS_REST =
      "(?:/p/|/)(.+)(?:/info/lfs/objects/batch)$";

  public static final String URL_REGEX =
      "^(?:/a)?" + LFS_REST;

  private static final Pattern URL_PATTERN = Pattern.compile(URL_REGEX);

  public static String parseProjectFromPath( final String path ) throws Exception {
    String pathInfo = path.startsWith("/") ? path : "/" + path;
    Matcher matcher = URL_PATTERN.matcher(pathInfo);
    if (!matcher.matches()) {
      throw new Exception("no repository at " + pathInfo);
    }
    String projName = matcher.group(1);
    return projName;
  }

}
