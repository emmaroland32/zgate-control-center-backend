package com.zgate.controlcenter.controller;

import com.zgate.controlcenter.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/summary")
    public ResponseEntity<ReportService.UsageSummaryReport> getSummary() {
        return ResponseEntity.ok(reportService.getUsageSummary());
    }

    @GetMapping("/modules")
    public ResponseEntity<List<ReportService.ModuleUsageStat>> getModuleStats() {
        return ResponseEntity.ok(reportService.getModuleStats());
    }

    @GetMapping("/deployments")
    public ResponseEntity<List<ReportService.DeploymentStat>> getDeploymentStats() {
        return ResponseEntity.ok(reportService.getDeploymentStats());
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestParam(defaultValue = "summary") String type) {
        String csv = reportService.exportCsv(type);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + type + ".csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(@RequestParam(defaultValue = "summary") String type) {
        // No PDF renderer available — return CSV with correct content type
        String csv = reportService.exportCsv(type);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report-" + type + ".csv")
            .contentType(MediaType.parseMediaType("text/csv"))
            .body(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
