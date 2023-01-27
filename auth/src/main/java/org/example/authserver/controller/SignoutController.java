package org.example.authserver.controller;

import lombok.extern.slf4j.Slf4j;
import org.example.authserver.service.AuthService;
import org.example.authserver.service.RedisService;
import org.example.authserver.service.SignoutService;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/signout")
public class SignoutController {

  private final SignoutService signoutService;

  private final AuthService authService;

  private final RedisService redis;

  public SignoutController(
      SignoutService signoutService, AuthService authService, RedisService redis) {
    this.signoutService = signoutService;
    this.authService = authService;
    this.redis = redis;
  }

  @PostMapping("/token/{tenant}/{jti}/{expirationTime}")
  public void signoutToken(
      @PathVariable String tenant, @PathVariable String jti, @PathVariable long expirationTime) {
    log.debug("token signout");
    signoutService.signoutToken(tenant, jti, expirationTime);
  }

  @PostMapping("/user/{tenant}/{userId}")
  public void signoutUser(@PathVariable String tenant, @PathVariable String userId) {
    log.debug("user signout");
    signoutService.signoutUser(tenant, userId);
  }

  @DeleteMapping("/user/{tenant}/{userId}")
  public void removeUserSignoutKey(@PathVariable String tenant, @PathVariable String userId) {
    log.debug("remove user signout key");
    authService.removeUserSignoutKey(userId);
  }
}
