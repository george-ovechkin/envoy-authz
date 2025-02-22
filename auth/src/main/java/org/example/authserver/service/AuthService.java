package org.example.authserver.service;

import authserver.common.CheckRequestDTO;
import authserver.common.CheckTestDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.rpc.Status;
import com.newrelic.api.agent.Trace;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.*;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.grpc.stub.StreamObserver;
import io.jsonwebtoken.Claims;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.example.authserver.config.AppProperties;
import org.example.authserver.config.Constants;
import org.example.authserver.entity.CheckResult;
import org.example.authserver.service.zanzibar.AclFilterService;
import org.example.authserver.service.zanzibar.TokenService;
import org.example.authserver.util.AuthzUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthService extends AuthorizationGrpc.AuthorizationImplBase {
  private static final Pattern pattern = Pattern.compile("(.*)\\/realms\\/(.*)");

  private static final Integer OK = 0;
  private static final Integer PERMISSION_DENIED = 7;
  private static final Integer UNAUTHORIZED = 16;
  private static final String TRACE_ID = "TRACEPARENT";

  private final AclFilterService aclFilterService;
  private final RedisService redisService;
  private final TokenService tokenService;
  private final SplitTestService splitTestService;
  private final AppProperties appProperties;

  public AuthService(
      AclFilterService aclFilterService,
      RedisService redisService,
      TokenService tokenService,
      SplitTestService splitTestService,
      AppProperties appProperties) {
    this.aclFilterService = aclFilterService;
    this.redisService = redisService;
    this.tokenService = tokenService;
    this.splitTestService = splitTestService;
    this.appProperties = appProperties;
  }

  @Trace(dispatcher = true)
  @Override
  public void check(CheckRequest request, StreamObserver<CheckResponse> responseObserver) {
    log.info(
        "request: {} {}",
        request.getAttributes().getRequest().getHttp().getMethod(),
        request.getAttributes().getRequest().getHttp().getPath());

    if (appProperties.isTokenSignOutCheckEnabled()) {
      CheckResponse unauthorizedCheckResult = validateTokenWithSignOutRequest(request);
      if (unauthorizedCheckResult != null) {
        responseObserver.onNext(unauthorizedCheckResult);
        responseObserver.onCompleted();

        return;
      }
    }

    CheckRequestDTO dto = CheckRequestMapper.request2dto(request);
    String traceId = getTraceId(request);

    CheckResult result = check(dto, traceId);

    HeaderValue headerAllowedTags =
        HeaderValue.newBuilder().setKey("X-ALLOWED-TAGS").setValue(result.getAllowedTags()).build();

    HeaderValueOption headers = HeaderValueOption.newBuilder().setHeader(headerAllowedTags).build();

    CheckResponse response =
        CheckResponse.newBuilder()
            .setStatus(Status.newBuilder().setCode(getCode(result.isResult())).build())
            .setOkResponse(OkHttpResponse.newBuilder().addHeaders(headers).build())
            .build();

    try {
      Map<String, Object> resultMap = result.getResultMap();

      String logEntry = AuthzUtils.prettyPrintObject(resultMap);
      log.info(logEntry);
    } catch (JsonProcessingException e) {
      log.warn("Can't read resultMap", e);
    }

    if (appProperties.isCopyModeEnabled()) {
      splitTestService.submitAsync(
          CheckTestDto.builder()
              .request(dto)
              .result(result.isResult())
              .resultHeaders(Map.of("X-ALLOWED-TAGS", result.getAllowedTags()))
              .time(new Date())
              .build());
    }

    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  public CheckResult check(CheckRequestDTO dto, String traceId) {
    String userId = dto.getUserId();
    String tenantId = dto.getTenant();

    if (userId == null || tenantId == null) {
      Claims claims = tokenService.getAllClaimsFromRequest(dto);
      if (claims != null) {
        userId = claims.getSubject();
        String issuer = claims.getIssuer();
        tenantId = getTenantId(issuer);
      }
    }

    CheckResult result;
    try {
      if (userId != null && tenantId != null) {
        result =
            aclFilterService.checkRequest(
                dto.getHttpMethod(), dto.getRequestPath(), dto.getHeadersMap(), userId, tenantId);
      } else {
        result =
            CheckResult.builder().jwtPresent(false).result(false).events(new HashMap<>()).build();
      }

    } catch (Exception e) {
      log.warn("Can't check request: {} {} ", dto.getHttpMethod(), dto.getRequestPath(), e);
      String stacktrace = ExceptionUtils.getStackTrace(e);

      Map<String, String> events = new HashMap<>();
      events.put("Exception", e.getMessage());
      events.put("Trace", stacktrace);

      result =
          CheckResult.builder()
              .httpMethod(dto.getHttpMethod())
              .requestPath(dto.getRequestPath())
              .result(false)
              .events(events)
              .build();
    }

    String allowedTags = String.join(",", result.getTags());
    result.setTraceId(traceId);
    result.setTenantId(tenantId);
    result.setAllowedTags(allowedTags);

    if (result.isMappingsPresent()) {
      if (!result.isResult()) {
        result.getEvents().put("REJECTED by mapping id", result.getRejectedWithMappingId());
        log.trace("REJECTED by mapping id: {}", result.getRejectedWithMappingId());
      }

    } else {
      result
          .getEvents()
          .put(
              "NO MAPPINGS found",
              String.format("%s %s", dto.getHttpMethod(), dto.getRequestPath()));
    }

    return result;
  }

  private int getCode(boolean allow) {
    return allow ? OK : PERMISSION_DENIED;
  }

  private CheckResponse validateTokenWithSignOutRequest(CheckRequest request) {
    try {
      Claims claims = tokenService.getAllClaimsFromRequest(request);
      String key = null;
      if (claims != null && claims.get("jti") != null) {
        key = String.format(Constants.SIGNOUT_REDIS_KEY, claims.get("jti").toString());
      }

      if (key == null) {
        return null;
      }

      if (redisService.exists(key)) {
        return CheckResponse.newBuilder()
            .setStatus(Status.newBuilder().setCode(UNAUTHORIZED))
            .setDeniedResponse(
                DeniedHttpResponse.newBuilder()
                    .setStatus(HttpStatus.newBuilder().setCode(StatusCode.Unauthorized).build())
                    .build())
            .build();
      }

    } catch (Exception ex) {
      log.warn("Redis service is unavailable");
    }

    return null;
  }

  private String getTraceId(CheckRequest checkRequest) {
    Map<String, String> headersMap =
        checkRequest.getAttributes().getRequest().getHttp().getHeadersMap();
    return headersMap.get(TRACE_ID);
  }

  private String getTenantId(String issuer) {
    Matcher m = pattern.matcher(issuer);
    if (m.matches() && m.groupCount() >= 2) {
      return m.group(2);
    }
    return null;
  }
}
