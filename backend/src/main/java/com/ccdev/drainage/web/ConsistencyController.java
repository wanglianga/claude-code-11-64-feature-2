package com.ccdev.drainage.web;

import com.ccdev.drainage.auth.AuthInterceptor;
import com.ccdev.drainage.domain.Models.AppUser;
import com.ccdev.drainage.domain.Models.ConsistencyReader;
import com.ccdev.drainage.domain.Models.ConsistencyReading;
import com.ccdev.drainage.domain.Models.ConsistencySample;
import com.ccdev.drainage.dto.Requests;
import com.ccdev.drainage.service.ConsistencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 承包商判读一致性数据服务接口：
 *   样本抽取、判读配对、分层一致性结果、证据查询、质量画像发布与版本查询。
 */
@RestController
@RequestMapping("/api/consistency")
public class ConsistencyController {

    private final ConsistencyService service;

    public ConsistencyController(ConsistencyService service) {
        this.service = service;
    }

    private AppUser user(HttpServletRequest request) {
        return (AppUser) request.getAttribute(AuthInterceptor.CURRENT_USER);
    }

    // ------------------------------------------------------------ 判读方与样本
    @GetMapping("/readers")
    public List<ConsistencyReader> readers() {
        return service.readers();
    }

    @GetMapping("/samples")
    public List<Map<String, Object>> samples(@RequestParam(required = false) String method,
                                             @RequestParam(required = false) String standardCode) {
        Map<Long, String> readerCode = new HashMap<>();
        for (ConsistencyReader r : service.readers()) {
            readerCode.put(r.id(), r.readerCode());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConsistencySample s : service.samples()) {
            boolean methodOk = method == null || method.isBlank() || "ALL".equalsIgnoreCase(method)
                    || ("DESIGNED".equalsIgnoreCase(method) && "DESIGNED_STRATIFIED".equals(s.sourceType()))
                    || ("NATURAL".equalsIgnoreCase(method) && "NATURAL_OVERLAP".equals(s.sourceType()));
            if (!methodOk || (standardCode != null && !standardCode.isBlank()
                    && !standardCode.equals(s.standardCode()))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sample", s);
            List<ConsistencyReading> readings = service.readingsOf(s.id());
            long active = readings.stream().filter(r -> Boolean.TRUE.equals(r.active())).count();
            row.put("activeReadingCount", active);
            row.put("totalReadingCount", readings.size());
            row.put("readers", readings.stream().filter(r -> Boolean.TRUE.equals(r.active()))
                    .map(r -> readerCode.getOrDefault(r.readerId(), "R#" + r.readerId())).toList());
            rows.add(row);
        }
        return rows;
    }

    @GetMapping("/samples/{id}")
    public Map<String, Object> sampleDetail(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sample", service.sample(id));
        result.put("readings", service.readingsOf(id));
        result.put("assignments", service.assignmentsOf(id));
        return result;
    }

    // ------------------------------------------------------------ 样本抽取
    @PostMapping("/samples/draw-designed")
    public Map<String, Object> drawDesigned(@Valid @RequestBody Requests.SampleDrawRequest req,
                                            HttpServletRequest request) {
        return service.drawDesignedSamples(req.standardCode(), req.perBandQuota(), req.readerIds(),
                req.note(), user(request).id());
    }

    @PostMapping("/samples/detect-natural")
    public Map<String, Object> detectNatural(HttpServletRequest request) {
        return service.detectNaturalOverlaps(user(request).id());
    }

    // ------------------------------------------------------------ 判读
    @PostMapping("/readings")
    public Map<String, Object> submitReading(@Valid @RequestBody Requests.ReadingSubmitRequest req,
                                             HttpServletRequest request) {
        return service.submitReading(req.sampleId(), req.readerId(), req.rawGrade(), req.blind(),
                req.independent(), req.seenPriorReadingId(), req.sourceBatchId(), req.note(),
                user(request).id());
    }

    @PostMapping("/readings/{id}/correct")
    public Map<String, Object> correctReading(@PathVariable Long id,
                                              @Valid @RequestBody Requests.ReadingCorrectRequest req,
                                              HttpServletRequest request) {
        return service.correctReading(id, req.rawGrade(), req.reason(), user(request).id());
    }

    // ------------------------------------------------------------ 分层一致性
    @GetMapping("/analysis")
    public Map<String, Object> analysis(@RequestParam(defaultValue = "ALL") String method,
                                        @RequestParam(required = false) String standardCode) {
        return service.analysis(method, standardCode);
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage(@RequestParam(defaultValue = "ALL") String method,
                                        @RequestParam(required = false) String standardCode) {
        return service.coverage(method, standardCode);
    }

    /** 证据查询：样本全部判读、配对差异、混淆来源与排除原因。 */
    @GetMapping("/samples/{id}/evidence")
    public Map<String, Object> evidence(@PathVariable Long id) {
        return service.evidence(id);
    }

    // ------------------------------------------------------------ 质量画像
    @GetMapping("/profiles")
    public List<?> profiles() {
        return service.profiles();
    }

    @PostMapping("/profiles/publish")
    public Map<String, Object> publish(@Valid @RequestBody Requests.ProfilePublishRequest req,
                                       HttpServletRequest request) {
        return service.publishProfile(req.method(), req.standardScope(), req.revisionReason(),
                user(request).id());
    }

    @GetMapping("/profiles/{id}")
    public Map<String, Object> profile(@PathVariable Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", service.profile(id));
        result.put("metrics", service.profileMetrics(id));
        result.put("gradesNotRewritten", true);
        return result;
    }
}
