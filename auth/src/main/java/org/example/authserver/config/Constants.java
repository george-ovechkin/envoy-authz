package org.example.authserver.config;

public class Constants {

  public static final String ACL_REDIS_KEY = "acls";
  public static final String ACL_REL_CONFIG_REDIS_KEY = "rel_configs";
  public static final String NEED_REFRESH_MAPPING_CACHE_MARKER_KEY =
          "NEED_REFRESH_MAPPING_CACHE_MARKER";
  public static final String TOKEN_SIGNOUT_REDIS_KEY = "TOKEN_SIGNOUT_%s";
  public static final String USER_SIGNOUT_REDIS_KEY = "USER_SIGNOUT_%s";
}
