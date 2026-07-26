package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.service.InfrastructureService;
import com.zgate.controlcenter.web.ResponseMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/infrastructure")
@RequiredArgsConstructor
public class InfrastructureController {

    private final InfrastructureService service;

    @GetMapping("/containers")
    public ResponseEntity<List<InfrastructureService.ContainerInfo>> listContainers() {
        return ResponseEntity.ok(service.listContainers());
    }

    @PostMapping("/containers/{id}/start")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "CONTAINER_STARTED", value = "Container started")
    public ResponseEntity<?> startContainer(@PathVariable String id) {
        service.startContainer(id);
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }

    @PostMapping("/containers/{id}/stop")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "CONTAINER_STOPPED", value = "Container stopped")
    public ResponseEntity<?> stopContainer(@PathVariable String id) {
        service.stopContainer(id);
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }

    @PostMapping("/containers/{id}/restart")
    @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('ADMIN')")
    @ResponseMessage(code = "CONTAINER_RESTARTED", value = "Container restarted")
    public ResponseEntity<?> restartContainer(@PathVariable String id) {
        service.restartContainer(id);
        return ResponseEntity.ok(java.util.Map.of("id", id));
    }

    @GetMapping("/containers/{id}/logs")
    public ResponseEntity<Map<String, String>> getContainerLogs(
            @PathVariable String id,
            @RequestParam(defaultValue = "200") int tail) {
        return ResponseEntity.ok(Map.of("logs", service.getContainerLogs(id, tail)));
    }

    @GetMapping("/containers/stats")
    public ResponseEntity<List<InfrastructureService.ContainerStats>> getStats() {
        return ResponseEntity.ok(service.getContainerStats());
    }

    @GetMapping("/volumes")
    public ResponseEntity<List<InfrastructureService.VolumeInfo>> listVolumes() {
        return ResponseEntity.ok(service.listVolumes());
    }

    @DeleteMapping("/volumes/{name}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @ResponseMessage(code = "VOLUME_DELETED", value = "Volume deleted")
    public ResponseEntity<?> deleteVolume(@PathVariable String name) {
        service.deleteVolume(name);
        return ResponseEntity.ok(java.util.Map.of("name", name));
    }

    @GetMapping("/compose/config")
    public ResponseEntity<Map<String, String>> getComposeConfig() {
        return ResponseEntity.ok(Map.of("config", service.getDockerComposeConfig()));
    }

    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> getResourceUsage() {
        return ResponseEntity.ok(service.getResourceUsage());
    }
}
