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
