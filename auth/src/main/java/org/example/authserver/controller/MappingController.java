package org.example.authserver.controller;

import java.util.List;
import java.util.Objects;
import javax.validation.Valid;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.example.authserver.config.AppProperties;
import org.example.authserver.entity.MappingEntity;
import org.example.authserver.entity.MappingEntityList;
import org.example.authserver.repo.MappingRepository;
import org.example.authserver.service.MappingCacheService;
import org.example.authserver.service.zanzibar.MappingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/mapping")
public class MappingController {
  public static final String MAPPING_PROTECTION_HEADER = "X-MAPPING-PROTECTION-HEADER";

  private final MappingRepository repository;
  private final MappingService mappingService;
  private final MappingCacheService mappingCacheService;

  private final String mappingsKey;
  private final boolean protectionMode;

  public MappingController(MappingRepository repository, MappingService mappingService, MappingCacheService mappingCacheService, AppProperties properties) {
    this.repository = repository;
    this.mappingService = mappingService;
    this.mappingCacheService = mappingCacheService;
    this.mappingsKey = properties.getMappingsProtectionKey();
    this.protectionMode = properties.isMappingsProtectionMode();
    if (protectionMode && Strings.isNullOrEmpty(mappingsKey)) {
      throw new IllegalStateException("Mappings key must present for turned on protection mode");
    }
  }

  @GetMapping("/list")
  public ResponseEntity<List<MappingEntity>> listMappings(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    return ResponseEntity.ok(mappingService.findAll());
  }

  @GetMapping("/check")
  public ResponseEntity<String> check(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    return ResponseEntity.ok("OK");
  }

  @PostMapping("/create")
  public ResponseEntity<String> addMapping(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header, @Valid @RequestBody MappingEntity mappingEntity) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    log.info("Created Mapping: {}", mappingEntity);
    mappingService.create(mappingEntity);
    return ResponseEntity.ok("");
  }

  @PostMapping("/create-many")
  public ResponseEntity<String> addMappings(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header, @Valid @RequestBody MappingEntityList dto) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    for (MappingEntity entity : dto.getMappings()) {
      addMapping(header, entity);
    }
    return ResponseEntity.ok("");
  }

  @DeleteMapping("/clear")
  public ResponseEntity<String> clearMappings(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    log.info("Delete Mappings");
    mappingService.deleteAll();
    return ResponseEntity.ok("");
  }

  @DeleteMapping("/delete/{id}")
  public ResponseEntity<String> deleteAcl(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header, @PathVariable String id) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    log.info("Delete Mapping by id: {}", id);
    repository.deleteById(id);
    return ResponseEntity.ok("");
  }

  @GetMapping("/refresh-cache")
  public ResponseEntity<String> notifyAllToRefreshCache(@RequestHeader(value="X-MAPPING-PROTECTION-HEADER") String header) {
    if (!validateKey(header)) return ResponseEntity.status(401).build();

    mappingCacheService.notifyAllToRefreshCache();
    return ResponseEntity.ok("");
  }

  private boolean validateKey(String key) {
    if (protectionMode && !Objects.equals(mappingsKey, key)) {
      return false;
    }
    return true;
  }
}
