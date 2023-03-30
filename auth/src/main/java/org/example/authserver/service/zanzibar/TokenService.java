package org.example.authserver.service.zanzibar;

import authserver.common.CheckRequestDTO;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtHandlerAdapter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.authserver.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class TokenService {

  private static final String AUTH_HEADER = "authorization";
  private static final String HEADER_MARK = "Bearer ";

  private final AppProperties appProperties;

  public TokenService(AppProperties appProperties) {
    this.appProperties = appProperties;
  }

  public Claims getAllClaimsFromRequest(CheckRequest request) {
    Optional<String> token = getTokenHeader(request);
    if (appProperties.isJwtParamEnabled()) {
      token = token.isEmpty() ? getTokenParam(request) : token;
    }
    if (appProperties.isAccessTokenCookieEnabled()) {
      Optional<String> cookieToken = getTokenCookie(request);
      token = (cookieToken.isEmpty()) ? token : cookieToken;
    }
    return token.isEmpty() ? null : getAllClaimsFromToken(token.get());
  }

  public Claims getAllClaimsFromRequest(CheckRequestDTO request) {
    Optional<String> token = getTokenHeader(request);
    if (appProperties.isJwtParamEnabled()) {
      token = token.isEmpty() ? getTokenParam(request) : token;
    }
    if (appProperties.isAccessTokenCookieEnabled()) {
      Optional<String> cookieToken = getTokenCookie(request);
      token = (cookieToken.isEmpty()) ? token : cookieToken;
    }
    return token.isEmpty() ? null : getAllClaimsFromToken(token.get());
  }

  private Optional<String> getTokenCookie(CheckRequest request) {
    Map<String, String> headers = request.getAttributes().getRequest().getHttp().getHeadersMap();
    CheckRequestDTO checkRequestDTO = CheckRequestDTO.builder().headersMap(headers).build();
    return getTokenCookie(checkRequestDTO);
  }

  private Optional<String> getTokenCookie(CheckRequestDTO request) {
    Map<String, String> map = request.getHeadersMap();
    if (map.get("cookie") == null) return Optional.empty();
    Map<String, String> cookies = new HashMap<>();
    String cookiesRaw = map.get("cookie");
    String[] tmp = cookiesRaw.split(";");
    for (String cookie : tmp) {
      String[] parts = cookie.split("=");
      if (parts.length != 2) continue;

      String key = parts[0];
      String val = parts[1];
      cookies.put(key.trim(), val);
    }

    return Optional.ofNullable(cookies.get(appProperties.getAccessTokenCookie()));
  }

  public Claims getAllClaimsFromToken(String token) {
    Claims claims;
    try {
      claims = parseClaimsJws(token).getBody();
    } catch (Exception e) {
      claims = null;
    }
    return claims;
  }

  public Optional<String> getTokenParam(CheckRequest request) {
    String path = request.getAttributes().getRequest().getHttp().getPath();
    return Optional.ofNullable(getTokenParam(path));
  }

  public Optional<String> getTokenParam(CheckRequestDTO request) {
    String path = request.getRequestPath();
    return Optional.ofNullable(getTokenParam(path));
  }

  public String getTokenParam(String path) {
    MultiValueMap<String, String> queryParams =
        UriComponentsBuilder.fromUriString(path).build().getQueryParams();

    List<String> value = queryParams.get(appProperties.getJwtParam());
    if (value != null && value.size() > 0) {
      return value.get(0);
    }
    return null;
  }

  public Optional<String> getTokenHeader(CheckRequest request) {
    Map<String, String> headers = request.getAttributes().getRequest().getHttp().getHeadersMap();
    String authHeader = headers.get(AUTH_HEADER);
    return Optional.ofNullable(getTokenHeader(authHeader));
  }

  public Optional<String> getTokenHeader(CheckRequestDTO request) {
    Map<String, String> headers = request.getHeadersMap();
    String authHeader = headers.get(AUTH_HEADER);
    return Optional.ofNullable(getTokenHeader(authHeader));
  }

  public String getTokenHeader(String authHeader) {
    if (authHeader != null
        && authHeader.startsWith(HEADER_MARK)
        && authHeader.length() > HEADER_MARK.length()) {
      return authHeader.substring(HEADER_MARK.length());
    }

    return null;
  }

  private Jws<Claims> parseClaimsJws(String claimsJws) {
    UnverifiedJwtParser parser = new UnverifiedJwtParser();
    return parser.parse(
        claimsJws,
        new JwtHandlerAdapter<Jws<Claims>>() {
          @Override
          public Jws<Claims> onClaimsJws(Jws<Claims> jws) {
            return jws;
          }
        });
  }
}
