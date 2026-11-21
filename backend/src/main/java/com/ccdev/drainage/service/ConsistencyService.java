package com.ccdev.drainage.service;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.Attribution;
import com.ccdev.drainage.domain.Models.ConsistencyAssignment;
import com.ccdev.drainage.domain.Models.ConsistencyProfile;
import com.ccdev.drainage.domain.Models.ConsistencyProfileMetric;
import com.ccdev.drainage.domain.Models.ConsistencyReader;
import com.ccdev.drainage.domain.Models.ConsistencyReading;
import com.ccdev.drainage.domain.Models.ConsistencySample;
import com.ccdev.drainage.domain.Models.ContractorBatch;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.domain.Models.Video;
import com.ccdev.drainage.repo.AnalysisRepository;
import com.ccdev.drainage.repo.AssetRepository;
import com.ccdev.drainage.repo.ConsistencyRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 承包商判读一致性服务。
 *
 * 三条硬约束（对照题目）：
 *   1. 同一视频（含重复上传）的片段在共同样本库中只建一个样本；多人重复评分只增加判读，
 *      不增加独立管段样本数。
 *   2. 只有“同规程版本 + 同缺陷类型 + 同严重度层”的判读进入同一分层；跨规程判读排除并计数。
 *   3. 非盲 / 看到首评后才打分的记录不作为独立盲评证据，排除并计数。
 *
 * 一致性估计以“留一法同侪共识”为配对基准：把某判读方对某样本的等级，与同一标准下其他
 * 独立盲评判读方对该样本的平均等级作差。这样比较发生在样本内部，避免把承包商负责区域
 * 的管段难度差异误当成评分偏差。自然重叠样本另给出区域分层，专门暴露空间难度不均。
 *
 * 质量画像只读判读记录，绝不回写 app_observation / app_defect_cluster 的已发布等级。
 */
@Service
public class ConsistencyService {

    static final int SPARSE_THRESHOLD = 3;
    static final int BOOTSTRAP_ROUNDS = 2000;

    private final ConsistencyRepository repo;
    private final InspectionRepository inspectionRepo;
    private final AnalysisRepository analysisRepo;
    private final AssetRepository assetRepo;
    private final UserRepository userRepo;

    public ConsistencyService(ConsistencyRepository repo, InspectionRepository inspectionRepo,
                              AnalysisRepository analysisRepo, AssetRepository assetRepo,
                              UserRepository userRepo) {
        this.repo = repo;
        this.inspectionRepo = inspectionRepo;
        this.analysisRepo = analysisRepo;
        this.assetRepo = assetRepo;
        this.userRepo = userRepo;
    }

    // ================================================================ 基础查询
    public List<ConsistencyReader> readers() {
        return repo.findAllReaders();
    }

    public List<ConsistencySample> samples() {
        return repo.findAllSamples();
    }

    public ConsistencySample sample(Long id) {
        return repo.findSampleById(id)
                .orElseThrow(() -> new ApiException(404, "共同样本不存在: " + id));
    }

    public List<ConsistencyReading> readingsOf(Long sampleId) {
        return repo.findReadingsBySample(sampleId);
    }

    public List<ConsistencyAssignment> assignmentsOf(Long sampleId) {
        return repo.findAssignmentsBySample(sampleId);
    }

    public List<ConsistencyProfile> profiles() {
        return repo.findAllProfiles();
    }

    public ConsistencyProfile profile(Long id) {
        return repo.findProfileById(id)
                .orElseThrow(() -> new ApiException(404, "质量画像不存在: " + id));
    }

    public List<ConsistencyProfileMetric> profileMetrics(Long profileId) {
        profile(profileId);
        return repo.findMetricsByProfile(profileId);
    }

    // ================================================================ 判读录入与更正
    /**
     * 录入判读。
     *   - 来源批次存在时以其规程版本为准，否则采用样本规程；
     *   - blind=false 或 seenPriorReadingId 非空 ⇒ independent 强制 false（非独立盲评）；
     *   - 同一判读方对同一样本已有有效判读时拒绝，更正必须走 correctReading。
     */
    @Transactional
    public Map<String, Object> submitReading(Long sampleId, Long readerId, String rawGrade,
                                             Boolean blind, Boolean independent, Long seenPriorReadingId,
                                             Long sourceBatchId, String note, Long userId) {
        ConsistencySample sample = sample(sampleId);
        repo.findReaderById(readerId)
                .orElseThrow(() -> new ApiException(404, "判读方不存在: " + readerId));
        if (repo.findActiveReading(sampleId, readerId).isPresent()) {
            throw new ApiException(409, "该判读方已对此样本给出有效判读；如需更正请使用更正接口（保留旧版本）");
        }

        String standard = sample.standardCode();
        ContractorBatch batch = null;
        if (sourceBatchId != null) {
            batch = inspectionRepo.findBatchById(sourceBatchId);
            standard = batch.gradingStandard();
        }
        Integer grade = inspectionRepo.normalizeGrade(standard, rawGrade);
        if (grade == null) {
            throw new ApiException(400, "原始等级 " + rawGrade + " 在规程 " + standard + " 中无映射");
        }

        Optional<ConsistencyAssignment> assignment = repo.findAssignment(sampleId, readerId);
        String blindIdentity = assignment.map(ConsistencyAssignment::blindIdentity)
                .orElse("MANUAL-" + sample.sampleCode() + "-" + readerId);
        boolean isBlind = blind == null || blind;
        boolean isIndependent = seenPriorReadingId == null
                && (independent == null || independent) && isBlind;

        ConsistencyReading reading = new ConsistencyReading(null, sampleId, readerId,
                assignment.map(ConsistencyAssignment::id).orElse(null), sourceBatchId,
                blindIdentity, standard, rawGrade, grade, isBlind, isIndependent,
                seenPriorReadingId, true, 1, null, null, null, note, LocalDateTime.now());
        long id = repo.insertReading(reading);
        assignment.ifPresent(a -> repo.markAssignmentSubmitted(a.id()));
        userRepo.audit(userId, "CONSISTENCY_READING", "sample:" + sampleId + "/reader:" + readerId,
                "等级 " + rawGrade + "(" + grade + ")，规程 " + standard
                        + (isIndependent ? "，独立盲评" : "，非独立盲评（不计入一致性）"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("readingId", id);
        result.put("normalizedGrade", grade);
        result.put("standardCode", standard);
        result.put("blind", isBlind);
        result.put("independent", isIndependent);
        result.put("eligibleAsEvidence", isIndependent && standard.equals(sample.standardCode()));
        if (!isIndependent) {
            result.put("exclusionReason", "该记录在看到首评后/非盲条件下产生，不能作为独立盲评证据");
        } else if (!standard.equals(sample.standardCode())) {
            result.put("exclusionReason", "判读规程 " + standard + " 与样本规程 "
                    + sample.standardCode() + " 不同，跨规程不可直接混算");
        }
        return result;
    }

    /** 承包商更正原判读：旧记录保留但下线，新版本 active，口径版本号 +1。 */
    @Transactional
    public Map<String, Object> correctReading(Long readingId, String rawGrade, String reason, Long userId) {
        ConsistencyReading old = repo.findReadingById(readingId)
                .orElseThrow(() -> new ApiException(404, "判读记录不存在: " + readingId));
        if (!Boolean.TRUE.equals(old.active())) {
            throw new ApiException(409, "该判读记录已被更新版本取代，请对最新有效记录更正");
        }
        ConsistencyReading root = old.revisionOfId() == null ? old
                : repo.findReadingById(old.revisionOfId()).orElse(old);
        Integer grade = inspectionRepo.normalizeGrade(old.standardCode(), rawGrade);
        if (grade == null) {
            throw new ApiException(400, "原始等级 " + rawGrade + " 在规程 " + old.standardCode() + " 中无映射");
        }
        ConsistencyReading revision = new ConsistencyReading(null, old.sampleId(), old.readerId(),
                old.assignmentId(), old.sourceBatchId(), old.blindIdentity(), old.standardCode(),
                rawGrade, grade, old.blind(), old.independent(), old.seenPriorReadingId(), true,
                old.revisionNo() + 1, root.id(), null, reason,
                "更正原判读 " + old.rawGrade() + " → " + rawGrade, LocalDateTime.now());
        // 先下线旧版本（部分唯一索引只允许一条 active），再插入新版本并回填取代指向
        repo.deactivateReading(old.id());
        long newId = repo.insertReading(revision);
        repo.linkSuperseded(old.id(), newId);
        ConsistencySample sample = sample(old.sampleId());
        userRepo.audit(userId, "CONSISTENCY_READING_CORRECTION", "sample:" + sample.sampleCode(),
                "判读 #" + old.id() + " 更正为 #" + newId + "：等级 " + old.rawGrade() + " → " + rawGrade
                        + "，原因：" + reason);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newReadingId", newId);
        result.put("supersededReadingId", old.id());
        result.put("revisionNo", revision.revisionNo());
        result.put("normalizedGrade", grade);
        result.put("affectedSampleCode", sample.sampleCode());
        result.put("affectedReaderId", old.readerId());
        result.put("affectedBatches", affectedBatchesOfReader(old.readerId(), old.sourceBatchId()));
        result.put("note", "旧判读仍可查询；质量画像需重新发布以生成新版本并列出受影响批次");
        return result;
    }

    // ================================================================ 样本抽取
    /**
     * 分层抽取共同视频样本：从日常复检中找出同管段、同缺陷类型、区间重叠且来自不同批次的观察，
     * 按严重度层配额建样本并派发匿名盲评身份。不会重复建同一片段。
     */
    @Transactional
    public Map<String, Object> drawDesignedSamples(String standardCode, Map<String, Integer> perBandQuota,
                                                   List<Long> readerIds, String note, Long userId) {
        if (readerIds == null || readerIds.isEmpty()) {
            throw new ApiException(400, "设计分层抽取需要至少指定一个判读方");
        }
        Map<String, Integer> quota = perBandQuota == null || perBandQuota.isEmpty()
                ? Map.of("MINOR", 2, "MODERATE", 2, "SEVERE", 1)
                : perBandQuota;
        List<CandidateClip> candidates = scanOverlapClips(standardCode);
        Map<String, Integer> used = new HashMap<>();
        int created = 0;
        List<String> createdCodes = new ArrayList<>();
        for (CandidateClip c : candidates) {
            String band = bandOf(c.consensusGrade);
            int limit = quota.getOrDefault(band, 0);
            if (used.getOrDefault(band, 0) >= limit) {
                continue;
            }
            String canonicalKey = "S:" + c.segmentCode + ":" + c.start.toPlainString() + "-"
                    + c.end.toPlainString() + ":" + c.defectType + ":" + standardCode;
            if (repo.findByCanonicalKey(canonicalKey).isPresent()) {
                continue;
            }
            // 编码包含片段起点，保证多次抽取、同层不同片段时 sample_code 仍唯一
            String code = "DS-G-" + standardCode + "-" + band + "-"
                    + c.segmentCode.replace('-', '_') + "-" + c.start.toPlainString();
            ConsistencySample sample = new ConsistencySample(null, code, "DESIGNED_STRATIFIED",
                    standardCode, c.defectType, band, c.regionCode, c.difficultyBand,
                    c.videoId, c.rootVideoId, canonicalKey, c.segmentCode, c.start, c.end,
                    c.consensusGrade, "DESIGN", "OPEN",
                    "scan:" + c.segmentCode + "@" + c.start.toPlainString() + "-" + c.end.toPlainString(),
                    note == null ? "分层抽取，判读方盲评" : note, userId, LocalDateTime.now());
            long sampleId = repo.insertSample(sample);
            for (Long readerId : readerIds) {
                repo.insertAssignment(sampleId, readerId, "BLD-" + code + "-" + readerId);
            }
            used.merge(band, 1, Integer::sum);
            created++;
            createdCodes.add(code);
        }
        userRepo.audit(userId, "CONSISTENCY_DRAW", "standard:" + standardCode,
                "分层抽取样本 " + created + " 个，判读方 " + readerIds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("standardCode", standardCode);
        result.put("createdSamples", created);
        result.put("sampleCodes", createdCodes);
        result.put("quota", quota);
        result.put("readerIds", readerIds);
        result.put("costNote", "设计分层样本精度高、便于形成可比较的配对差异，但需要额外复核工时，样本更新较慢");
        return result;
    }

    /**
     * 自然重叠建模：利用日常任务中不同承包商对同一位置的复检记录建立样本。
     * 覆盖更多真实案件，但受各家负责区域难度不同与复检选择偏差影响，须与区域分层一起解读。
     */
    @Transactional
    public Map<String, Object> detectNaturalOverlaps(Long userId) {
        List<CandidateClip> candidates = scanOverlapClips(null);
        int created = 0;
        List<String> codes = new ArrayList<>();
        Map<String, Integer> byRegion = new TreeMap<>();
        for (CandidateClip c : candidates) {
            String canonicalKey = "S:" + c.segmentCode + ":" + c.start.toPlainString() + "-"
                    + c.end.toPlainString() + ":" + c.defectType + ":" + c.standardCode;
            if (repo.findByCanonicalKey(canonicalKey).isPresent()) {
                continue;
            }
            String band = bandOf(c.consensusGrade);
            String code = "NS-G-" + c.standardCode + "-" + c.segmentCode.replace('-', ' ').trim()
                    .replace(' ', '_') + "-" + c.defectType + "-" + c.start.toPlainString();
            ConsistencySample sample = new ConsistencySample(null, code, "NATURAL_OVERLAP",
                    c.standardCode, c.defectType, band, c.regionCode, c.difficultyBand,
                    c.videoId, c.rootVideoId, canonicalKey, c.segmentCode, c.start, c.end,
                    null, "PEER_CONSENSUS", "OPEN",
                    "batches:" + c.batchIds, "日常复检自然重叠，未经追加盲评", userId, LocalDateTime.now());
            long sampleId = repo.insertSample(sample);
            for (Long readerId : c.readerIds) {
                repo.insertAssignment(sampleId, readerId, "BLD-" + code + "-" + readerId);
            }
            created++;
            codes.add(code);
            byRegion.merge(c.regionCode, 1, Integer::sum);
        }
        userRepo.audit(userId, "CONSISTENCY_NATURAL", "all",
                "自然重叠样本 " + created + " 个，区域分布 " + byRegion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdSamples", created);
        result.put("sampleCodes", codes);
        result.put("regionDistribution", byRegion);
        result.put("biasWarning", "自然重叠覆盖更多真实案件，但各承包商负责区域难度不同、复检选择有偏，"
                + "偏差解读必须结合区域与难度分层，不能直接外推到全量管段");
        return result;
    }

    private record CandidateClip(long videoId, long rootVideoId, String segmentCode, String defectType,
                                 String standardCode, BigDecimal start, BigDecimal end,
                                 Integer consensusGrade, String regionCode, String difficultyBand,
                                 String batchIds, Set<Long> readerIds) {
    }

    /**
     * 扫描同管段 + 同缺陷类型 + 区间重叠且不同批次的观察，得到可用作共同样本的片段。
     * 视频为重复上传时归并到根视频，避免同一视频重复计入多个样本。
     */
    private List<CandidateClip> scanOverlapClips(String standardFilter) {
        List<Attribution> attributions = analysisRepo.findAllAttributions();
        Map<Long, Attribution> attrByObs = new HashMap<>();
        for (Attribution a : attributions) {
            attrByObs.put(a.observationId(), a);
        }
        Map<Long, Video> videos = new HashMap<>();
        for (Video v : inspectionRepo.findAllVideos()) {
            videos.put(v.id(), v);
        }
        Map<Long, ContractorBatch> batches = new HashMap<>();
        for (ContractorBatch b : inspectionRepo.findAllBatches()) {
            batches.put(b.id(), b);
        }
        Map<String, ConsistencyReader> readerByVendorName = new HashMap<>();
        for (ConsistencyReader r : repo.findAllReaders()) {
            if (r.vendorName() != null) {
                readerByVendorName.put(r.vendorName(), r);
            }
        }

        // 既有样本提供区域/难度标注参考
        Map<String, String> regionBySegment = new HashMap<>();
        Map<String, String> difficultyBySegment = new HashMap<>();
        for (ConsistencySample s : repo.findAllSamples()) {
            if (s.segmentCode() != null) {
                regionBySegment.putIfAbsent(s.segmentCode(), s.regionCode());
                difficultyBySegment.putIfAbsent(s.segmentCode(), s.difficultyBand());
            }
        }

        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Observation o : inspectionRepo.findAllObservations()) {
            Attribution attr = attrByObs.get(o.id());
            if (attr == null) {
                continue;
            }
            Video video = videos.get(o.videoId());
            if (video == null) {
                continue;
            }
            ContractorBatch batch = batches.get(o.batchId());
            if (batch == null) {
                continue;
            }
            if (standardFilter != null && !standardFilter.equals(batch.gradingStandard())) {
                continue;
            }
            Integer grade = inspectionRepo.normalizeGrade(batch.gradingStandard(), o.rawGrade());
            if (grade == null) {
                continue;
            }
            String segmentCode = assetRepo.findSegmentById(attr.segmentId()).segmentCode();
            // 按“管段 + 缺陷类型 + 规程版本”分组：跨规程版本的重叠不得混算
            groups.computeIfAbsent(segmentCode + "|" + o.defectType() + "|" + batch.gradingStandard(),
                            k -> new ArrayList<>())
                    .add(new Row(o, attr, video, batch, grade, segmentCode));
        }

        List<CandidateClip> result = new ArrayList<>();
        for (List<Row> rows : groups.values()) {
            rows.sort(Comparator.comparing(r -> r.attr.normStartM()));
            // 以不同批次为独立来源做区间重叠归并
            int i = 0;
            while (i < rows.size()) {
                List<Row> cluster = new ArrayList<>();
                BigDecimal clusterEnd = rows.get(i).attr.normEndM();
                cluster.add(rows.get(i));
                int j = i + 1;
                while (j < rows.size()
                        && rows.get(j).attr.normStartM().compareTo(clusterEnd.add(new BigDecimal("0.500"))) <= 0) {
                    cluster.add(rows.get(j));
                    if (rows.get(j).attr.normEndM().compareTo(clusterEnd) > 0) {
                        clusterEnd = rows.get(j).attr.normEndM();
                    }
                    j++;
                }
                Set<Long> batchIds = new LinkedHashSet<>();
                Set<String> standards = new LinkedHashSet<>();
                Set<Long> readerIds = new LinkedHashSet<>();
                List<Integer> grades = new ArrayList<>();
                for (Row r : cluster) {
                    batchIds.add(r.batch.id());
                    standards.add(r.batch.gradingStandard());
                    grades.add(r.grade);
                    ConsistencyReader reader = readerByVendorName.get(r.batch.contractor());
                    if (reader != null) {
                        readerIds.add(reader.id());
                    }
                }
                // 只有跨批次、单一规程、至少两家判读方的重叠才有配对价值
                if (batchIds.size() >= 2 && standards.size() == 1 && readerIds.size() >= 2) {
                    Row anchor = cluster.stream()
                            .max(Comparator.comparing(r -> r.attr.normEndM().subtract(r.attr.normStartM())))
                            .orElseThrow();
                    String standard = standards.iterator().next();
                    String segmentCode = anchor.segmentCode;
                    String region = regionBySegment.getOrDefault(segmentCode,
                            "AREA-" + (anchor.attr.normStartM().intValue() % 2 == 0 ? "A" : "B"));
                    String difficulty = difficultyBySegment.getOrDefault(segmentCode,
                            anchor.batch.gradingStandard().equals("STD-DX-2023") ? "HIGH" : "MID");
                    List<Integer> sortedGrades = new ArrayList<>(grades);
                    sortedGrades.sort(Integer::compare);
                    int consensus = sortedGrades.get(sortedGrades.size() / 2);
                    result.add(new CandidateClip(anchor.obs.videoId(), rootVideoId(anchor.video, videos),
                            segmentCode, anchor.obs.defectType(), standard,
                            anchor.attr.normStartM(), anchor.attr.normEndM(), consensus,
                            region, difficulty,
                            batchIds.toString(), readerIds));
                }
                i = j;
            }
        }
        result.sort(Comparator.comparing(CandidateClip::segmentCode)
                .thenComparing(CandidateClip::defectType)
                .thenComparing(CandidateClip::start));
        return result;
    }

    private record Row(Observation obs, Attribution attr, Video video, ContractorBatch batch,
                       Integer grade, String segmentCode) {
    }

    private long rootVideoId(Video video, Map<Long, Video> all) {
        Video current = video;
        Set<Long> seen = new HashSet<>();
        while (current.duplicateOfId() != null && seen.add(current.id())) {
            Video parent = all.get(current.duplicateOfId());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current.id();
    }

    // ================================================================ 一致性计算
    /**
     * 实时分层一致性结果：按 规程 × 缺陷类型 × 严重度（自然样本另加区域）输出
     * 配对差异、混淆分布与自助法不确定区间。
     */
    public Map<String, Object> analysis(String method, String standardCode) {
        Prepared prep = prepare(method, standardCode);
        List<Map<String, Object>> strata = new ArrayList<>();
        for (Map.Entry<StratumKey, StratumAccumulator> e : prep.strata.entrySet()) {
            strata.add(stratumRow(e.getKey(), e.getValue()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", prep.method);
        result.put("standardScope", prep.scope);
        result.put("summary", summary(prep));
        result.put("strata", strata);
        result.put("warnings", coverageWarnings(prep));
        result.put("excluded", Map.of(
                "nonBlindReadings", prep.excludedNonBlind,
                "crossStandardReadings", prep.excludedCrossStandard,
                "duplicateClipSamplesMerged", prep.mergedDuplicateSamples));
        return result;
    }

    /**
     * 证据查询：某样本的全部判读、配对差异与排除原因，用于复核与审计。
     */
    public Map<String, Object> evidence(Long sampleId) {
        ConsistencySample sample = sample(sampleId);
        List<ConsistencyReading> readings = repo.findReadingsBySample(sampleId);
        Map<Long, ConsistencyReader> readers = new HashMap<>();
        for (ConsistencyReader r : repo.findAllReaders()) {
            readers.put(r.id(), r);
        }
        List<ConsistencyReading> eligible = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (ConsistencyReading r : readings) {
            if (!Boolean.TRUE.equals(r.active())) {
                continue;
            }
            String reason = exclusionReason(r, sample);
            if (reason == null) {
                eligible.add(r);
            } else {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("reading", r);
                row.put("readerCode", readers.getOrDefault(r.readerId(), null));
                row.put("reason", reason);
                excluded.add(row);
            }
        }
        List<Integer> grades = eligible.stream().map(ConsistencyReading::normalizedGrade).toList();
        double consensus = grades.isEmpty() ? 0 : mean(grades);
        int consensusRounded = clampBand(Math.round((float) consensus));
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (ConsistencyReading r : eligible) {
            List<Integer> others = new ArrayList<>(grades);
            others.remove((Integer) r.normalizedGrade());
            if (others.isEmpty()) {
                continue;
            }
            double peer = mean(others);
            double diff = r.normalizedGrade() - peer;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("readingId", r.id());
            row.put("readerId", r.readerId());
            row.put("readerCode", readers.containsKey(r.readerId()) ? readers.get(r.readerId()).readerCode() : null);
            row.put("rawGrade", r.rawGrade());
            row.put("normalizedGrade", r.normalizedGrade());
            row.put("standardCode", r.standardCode());
            row.put("blindIdentity", r.blindIdentity());
            row.put("blind", r.blind());
            row.put("independent", r.independent());
            row.put("peerMean", round(peer, 3));
            row.put("pairedDifference", round(diff, 3));
            row.put("direction", diff > 0 ? "STRICTER" : diff < 0 ? "LOOSER" : "AGREE");
            row.put("revisionNo", r.revisionNo());
            pairs.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sample", sample);
        result.put("effectiveSeverityBand", effectiveBand(sample, eligible));
        result.put("consensusMean", round(consensus, 3));
        result.put("consensusGrade", consensusRounded);
        result.put("eligibleReadingCount", eligible.size());
        result.put("pairs", pairs);
        result.put("excludedReadings", excluded);
        result.put("allReadings", readings);
        result.put("rules", List.of(
                "同一视频的重复上传归并为同一片段，只算一个样本",
                "跨规程版本的判读不参与本样本配对",
                "看到首评后才打分或非盲记录不作为独立盲评证据"));
        return result;
    }

    // ================================================================ 质量画像发布
    /**
     * 发布质量画像：只读判读记录，按方法/规程范围生成新版本；同一 (method, scope) 的旧版本标记 SUPERSEDED
     * 但仍可查询。发生判读更正时 affected_batches 列出受影响批次。
     */
    @Transactional
    public Map<String, Object> publishProfile(String method, String standardScope, String revisionReason,
                                              Long userId) {
        String normalizedMethod = normalizeMethod(method);
        String scope = standardScope == null || standardScope.isBlank() ? "ALL" : standardScope;
        Prepared prep = prepare(normalizedMethod, scope.equals("ALL") ? null : scope);
        if (prep.samples.isEmpty()) {
            throw new ApiException(400, "所选范围没有可用共同样本，无法发布质量画像");
        }
        Optional<ConsistencyProfile> previous = repo.findLatestProfile(normalizedMethod, scope);
        previous.ifPresent(p -> repo.supersedeProfile(p.id()));

        int version = repo.nextProfileVersion(normalizedMethod, scope);
        String code = "QP-" + normalizedMethod + "-" + scope + "-V" + version;
        String sparse = prep.sparseReaderCodes();
        String severityNote = severityCoverageNote(prep);
        String coverageNote = coverageNote(prep);
        String affected = affectedBatchesSince(previous.map(ConsistencyProfile::generatedAt).orElse(null),
                prep.readerIdsInScope);

        ConsistencyProfile profile = new ConsistencyProfile(null, code, normalizedMethod, scope, version,
                "PUBLISHED", prep.samples.size(), prep.independentSampleCount, prep.readingCount,
                prep.pairCount(), prep.excludedNonBlind, prep.excludedCrossStandard, sparse,
                severityNote, coverageNote, revisionReason, affected,
                previous.map(ConsistencyProfile::id).orElse(null), LocalDateTime.now(), userId);
        long profileId = repo.insertProfile(profile);

        for (Map.Entry<StratumKey, StratumAccumulator> e : prep.strata.entrySet()) {
            StratumAccumulator acc = e.getValue();
            StratumKey key = e.getKey();
            for (Map.Entry<Long, List<PairObs>> re : acc.pairsByReader.entrySet()) {
                List<PairObs> pairs = re.getValue();
                if (pairs.isEmpty()) {
                    continue;
                }
                Stats stats = Stats.of(pairs);
                boolean sparseRow = stats.distinctSamples < SPARSE_THRESHOLD;
                String status = sparseRow ? "SPARSE" : "OK";
                String note = sparseRow
                        ? "该分层有效样本 " + stats.distinctSamples + " 个，低于 " + SPARSE_THRESHOLD
                        + "，区间仅供定性参考，不可据此定论"
                        : "留一法同侪共识配对，样本内比较已抵消管段难度差异";
                ConsistencyProfileMetric metric = new ConsistencyProfileMetric(null, profileId, re.getKey(),
                        key.standardCode, key.defectType, key.severityBand, key.regionCode,
                        pairs.size(), stats.distinctSamples, round(stats.meanBias, 3),
                        round(stats.biasLow, 3), round(stats.biasHigh, 3),
                        round(stats.disagreement, 3), round(stats.agreement, 4),
                        round(stats.agreementLow, 4), round(stats.agreementHigh, 4),
                        round(stats.withinOne, 4), stats.stricter, stats.looser, status, note);
                repo.insertMetric(profileId, metric);
            }
        }
        userRepo.audit(userId, "CONSISTENCY_PROFILE_PUBLISH", "profile:" + code,
                "方法 " + normalizedMethod + "，规程 " + scope + "，版本 V" + version
                        + "，配对 " + prep.pairCount() + "；仅发布画像，不回写已发布缺陷等级");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profileId", profileId);
        result.put("profileCode", code);
        result.put("versionNo", version);
        result.put("status", "PUBLISHED");
        result.put("supersedesProfileId", previous.map(ConsistencyProfile::id).orElse(null));
        result.put("sampleCount", prep.samples.size());
        result.put("pairedObservationCount", prep.pairCount());
        result.put("sparseReaderCodes", sparse);
        result.put("severityCoverageNote", severityNote);
        result.put("coverageNote", coverageNote);
        result.put("affectedBatches", affected);
        result.put("gradesNotRewritten", true);
        return result;
    }

    public Map<String, Object> coverage(String method, String standardCode) {
        Prepared prep = prepare(method, standardCode);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("method", prep.method);
        result.put("standardScope", prep.scope);
        result.put("summary", summary(prep));
        result.put("bandCoverage", bandCoverage(prep));
        result.put("regionCoverage", regionCoverage(prep));
        result.put("warnings", coverageWarnings(prep));
        return result;
    }

    // ================================================================ 内部计算
    private String normalizeMethod(String method) {
        String m = method == null ? "ALL" : method.toUpperCase();
        if (!Set.of("DESIGNED", "NATURAL", "ALL").contains(m)) {
            throw new ApiException(400, "method 仅支持 DESIGNED / NATURAL / ALL");
        }
        return m;
    }

    private boolean methodMatches(String method, ConsistencySample s) {
        return switch (method) {
            case "DESIGNED" -> "DESIGNED_STRATIFIED".equals(s.sourceType());
            case "NATURAL" -> "NATURAL_OVERLAP".equals(s.sourceType());
            default -> true;
        };
    }

    private record StratumKey(String standardCode, String defectType, String severityBand, String regionCode) {
    }

    private record PairObs(long sampleId, long readerId, int observed, double peerMean) {
    }

    private static final class StratumAccumulator {
        final Map<Long, List<PairObs>> pairsByReader = new TreeMap<>();
        final Set<Long> distinctSamples = new HashSet<>();
        final List<Integer> observedGrades = new ArrayList<>();
        final List<Integer> consensusGrades = new ArrayList<>();
        final int[][] confusion = new int[5][5];
    }

    private static final class Prepared {
        String method;
        String scope;
        final List<ConsistencySample> samples = new ArrayList<>();
        final Map<Long, ConsistencySample> sampleById = new HashMap<>();
        final List<ConsistencyReading> readings = new ArrayList<>();
        final Map<Long, List<ConsistencyReading>> readingsBySample = new HashMap<>();
        final List<EvidencePair> pairs = new ArrayList<>();
        final Map<StratumKey, StratumAccumulator> strata = new LinkedHashMap<>();
        final Set<Long> readerIdsInScope = new HashSet<>();
        int excludedNonBlind;
        int excludedCrossStandard;
        int mergedDuplicateSamples;
        int independentSampleCount;
        int readingCount;

        int pairCount() {
            return pairs.size();
        }

        /** 单家样本稀少：按“不同样本数”计，避免区域重复配对把计数放大。 */
        String sparseReaderCodes() {
            Map<Long, Set<Long>> samplesByReader = new TreeMap<>();
            for (EvidencePair p : pairs) {
                samplesByReader.computeIfAbsent(p.readerId, k -> new HashSet<>()).add(p.sampleId);
            }
            List<String> codes = new ArrayList<>();
            for (Map.Entry<Long, Set<Long>> e : samplesByReader.entrySet()) {
                int distinct = e.getValue().size();
                if (distinct < SPARSE_THRESHOLD) {
                    readerIdsToCode.computeIfAbsent(e.getKey(), k -> "R#" + k);
                    codes.add(readerIdsToCode.get(e.getKey()) + "(" + distinct + ")");
                }
            }
            return codes.isEmpty() ? null : String.join("，", codes);
        }

        final Map<Long, String> readerIdsToCode = new HashMap<>();
    }

    private record EvidencePair(long sampleId, long readerId, String standardCode, String defectType,
                                String severityBand, String regionCode, int observed, int consensus,
                                double peerMean) {
    }

    private Prepared prepare(String method, String standardCode) {
        Prepared prep = new Prepared();
        prep.method = normalizeMethod(method);
        prep.scope = standardCode == null || standardCode.isBlank() ? "ALL" : standardCode;
        Map<Long, ConsistencyReader> readerById = new HashMap<>();
        for (ConsistencyReader r : repo.findAllReaders()) {
            readerById.put(r.id(), r);
            prep.readerIdsToCode.put(r.id(), r.readerCode());
        }

        // 1. 选样本；同一片段跨方法只保留一次（设计分层优先，再自然重叠）
        List<ConsistencySample> ordered = new ArrayList<>(repo.findAllSamples());
        ordered.sort(Comparator.comparing((ConsistencySample s) ->
                        "DESIGNED_STRATIFIED".equals(s.sourceType()) ? 0 : 1)
                .thenComparing(ConsistencySample::id));
        Set<String> seenClips = new HashSet<>();
        for (ConsistencySample s : ordered) {
            if (!methodMatches(prep.method, s)) {
                continue;
            }
            if (!"ALL".equals(prep.scope) && !prep.scope.equals(s.standardCode())) {
                continue;
            }
            // 规范片段键：管段 + 片段区间 + 缺陷类型 + 规程。重复上传视频的同一片段自然落到同键，
            // 从而同一视频被多人重复评分只算一个样本。
            String clip = "S:" + s.segmentCode() + ":" + s.clipStartM() + "-" + s.clipEndM()
                    + ":" + s.defectType() + ":" + s.standardCode();
            if (!seenClips.add(clip)) {
                prep.mergedDuplicateSamples++;
                continue;
            }
            prep.samples.add(s);
            prep.sampleById.put(s.id(), s);
            List<ConsistencyReading> all = repo.findReadingsBySample(s.id());
            List<ConsistencyReading> eligible = new ArrayList<>();
            for (ConsistencyReading r : all) {
                if (!Boolean.TRUE.equals(r.active())) {
                    continue;
                }
                String reason = exclusionReason(r, s);
                if (reason == null) {
                    eligible.add(r);
                } else if (reason.contains("规程")) {
                    prep.excludedCrossStandard++;
                } else {
                    prep.excludedNonBlind++;
                }
            }
            prep.readings.addAll(all);
            prep.readingsBySample.put(s.id(), all);
            prep.readingCount += eligible.size();
            if (eligible.size() >= 2) {
                prep.independentSampleCount++;
            }
            int consensus = clampBand(Math.round((float) mean(
                    eligible.stream().map(ConsistencyReading::normalizedGrade).toList())));
            for (ConsistencyReading r : eligible) {
                List<Integer> others = new ArrayList<>();
                for (ConsistencyReading o : eligible) {
                    if (!o.id().equals(r.id())) {
                        others.add(o.normalizedGrade());
                    }
                }
                if (others.isEmpty()) {
                    continue;
                }
                double peer = mean(others);
                // 严重度层以抽样时的分层登记为准（样本属性），不随个别更正漂移
                String band = s.severityBand();
                StratumKey key = new StratumKey(s.standardCode(), s.defectType(), band, "ALL");
                StratumAccumulator acc = prep.strata.computeIfAbsent(key, k -> new StratumAccumulator());
                acc.pairsByReader.computeIfAbsent(r.readerId(), k -> new ArrayList<>())
                        .add(new PairObs(s.id(), r.readerId(), r.normalizedGrade(), peer));
                acc.distinctSamples.add(s.id());
                acc.observedGrades.add(r.normalizedGrade());
                acc.consensusGrades.add(consensus);
                acc.confusion[r.normalizedGrade()][consensus]++;
                prep.pairs.add(new EvidencePair(s.id(), r.readerId(), s.standardCode(), s.defectType(),
                        band, "ALL", r.normalizedGrade(), consensus, peer));
                prep.readerIdsInScope.add(r.readerId());

                if (!"UNKNOWN".equals(s.regionCode())) {
                    StratumKey regionKey = new StratumKey(s.standardCode(), s.defectType(),
                            band, s.regionCode());
                    StratumAccumulator racc = prep.strata.computeIfAbsent(regionKey, k -> new StratumAccumulator());
                    racc.pairsByReader.computeIfAbsent(r.readerId(), k -> new ArrayList<>())
                            .add(new PairObs(s.id(), r.readerId(), r.normalizedGrade(), peer));
                    racc.distinctSamples.add(s.id());
                    racc.observedGrades.add(r.normalizedGrade());
                    racc.consensusGrades.add(consensus);
                    racc.confusion[r.normalizedGrade()][consensus]++;
                }
            }
        }
        return prep;
    }

    private String exclusionReason(ConsistencyReading r, ConsistencySample sample) {
        if (!Boolean.TRUE.equals(r.blind()) || !Boolean.TRUE.equals(r.independent())
                || r.seenPriorReadingId() != null) {
            return "非盲或看到首评后才打分，不能作为独立盲评证据";
        }
        if (!r.standardCode().equals(sample.standardCode())) {
            return "判读规程 " + r.standardCode() + " 与样本规程 " + sample.standardCode()
                    + " 不同，跨规程等级不可直接混算";
        }
        return null;
    }

    private int effectiveBandGrade(ConsistencySample s, List<ConsistencyReading> eligible) {
        if (s.referenceGrade() != null) {
            return s.referenceGrade();
        }
        if (eligible.isEmpty()) {
            return 2;
        }
        List<Integer> grades = eligible.stream().map(ConsistencyReading::normalizedGrade).sorted().toList();
        return grades.get(grades.size() / 2);
    }

    private String effectiveBand(ConsistencySample s, List<ConsistencyReading> eligible) {
        return bandOf(effectiveBandGrade(s, eligible));
    }

    static String bandOf(int grade) {
        if (grade <= 2) {
            return "MINOR";
        }
        if (grade == 3) {
            return "MODERATE";
        }
        return "SEVERE";
    }

    private Map<String, Object> stratumRow(StratumKey key, StratumAccumulator acc) {
        List<Map<String, Object>> readerRows = new ArrayList<>();
        for (Map.Entry<Long, List<PairObs>> e : acc.pairsByReader.entrySet()) {
            Stats stats = Stats.of(e.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("readerId", e.getKey());
            row.put("pairedN", e.getValue().size());
            row.put("distinctSampleN", stats.distinctSamples);
            row.put("meanBias", round(stats.meanBias, 3));
            row.put("biasCi", List.of(round(stats.biasLow, 3), round(stats.biasHigh, 3)));
            row.put("disagreementIndex", round(stats.disagreement, 3));
            row.put("agreementRate", round(stats.agreement, 4));
            row.put("agreementCi", List.of(round(stats.agreementLow, 4), round(stats.agreementHigh, 4)));
            row.put("withinOneRate", round(stats.withinOne, 4));
            row.put("stricterCount", stats.stricter);
            row.put("looserCount", stats.looser);
            row.put("sparse", stats.distinctSamples < SPARSE_THRESHOLD);
            readerRows.add(row);
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("standardCode", key.standardCode);
        row.put("defectType", key.defectType);
        row.put("severityBand", key.severityBand);
        row.put("regionCode", key.regionCode);
        row.put("distinctSampleN", acc.distinctSamples.size());
        row.put("pairedN", acc.pairsByReader.values().stream().mapToInt(List::size).sum());
        row.put("confusion", confusionMatrix(acc.confusion));
        row.put("readers", readerRows);
        return row;
    }

    private List<Map<String, Object>> confusionMatrix(int[][] matrix) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int observed = 1; observed <= 4; observed++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("observedGrade", observed);
            for (int consensus = 1; consensus <= 4; consensus++) {
                row.put("consensus" + consensus, matrix[observed][consensus]);
            }
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> summary(Prepared prep) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sampleCount", prep.samples.size());
        summary.put("independentSampleCount", prep.independentSampleCount);
        summary.put("readingCount", prep.readingCount);
        summary.put("pairedObservationCount", prep.pairCount());
        summary.put("excludedNonBlind", prep.excludedNonBlind);
        summary.put("excludedCrossStandard", prep.excludedCrossStandard);
        summary.put("mergedDuplicateSamples", prep.mergedDuplicateSamples);
        summary.put("distinctReaders", prep.readerIdsInScope.size());
        return summary;
    }

    private Map<String, Integer> bandCoverage(Prepared prep) {
        Map<String, Integer> coverage = new LinkedHashMap<>();
        for (String band : List.of("MINOR", "MODERATE", "SEVERE")) {
            coverage.put(band, 0);
        }
        for (ConsistencySample s : prep.samples) {
            List<ConsistencyReading> eligible = prep.readingsBySample.getOrDefault(s.id(), List.of()).stream()
                    .filter(r -> exclusionReason(r, s) == null).toList();
            coverage.merge(s.severityBand(), 1, Integer::sum);
        }
        return coverage;
    }

    private Map<String, Integer> regionCoverage(Prepared prep) {
        Map<String, Integer> coverage = new TreeMap<>();
        for (ConsistencySample s : prep.samples) {
            coverage.merge(s.regionCode(), 1, Integer::sum);
        }
        return coverage;
    }

    private List<String> coverageWarnings(Prepared prep) {
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> bands = bandCoverage(prep);
        if (bands.getOrDefault("SEVERE", 0) == 0) {
            warnings.add("共享样本未覆盖 SEVERE 层：不能据此声称各方对严重结构问题判读一致");
        } else if (bands.getOrDefault("SEVERE", 0) < SPARSE_THRESHOLD) {
            warnings.add("SEVERE 层样本仅 " + bands.get("SEVERE") + " 个，低于 " + SPARSE_THRESHOLD
                    + "：对严重结构问题的一致性结论不稳定");
        }
        if (bands.getOrDefault("MINOR", 0) + bands.getOrDefault("MODERATE", 0) > 0
                && bands.getOrDefault("SEVERE", 0) == 0) {
            warnings.add("现有样本集中在轻微/中等缺陷，结论不得外推到严重缺陷");
        }
        Map<String, Integer> regions = regionCoverage(prep);
        if ("NATURAL".equals(prep.method) && regions.size() > 1) {
            warnings.add("自然重叠样本区域分布不均（" + regions + "）：各家负责区域难度不同，"
                    + "偏差估计受空间难度与复检选择偏差影响");
        }
        if (prep.excludedNonBlind > 0) {
            warnings.add("已排除 " + prep.excludedNonBlind + " 条非盲/看到首评后的记录，不作为一致性和偏差证据");
        }
        if (prep.excludedCrossStandard > 0) {
            warnings.add("已排除 " + prep.excludedCrossStandard + " 条跨规程判读，等级按规程版本分层、不混算");
        }
        if (prep.mergedDuplicateSamples > 0) {
            warnings.add("已合并 " + prep.mergedDuplicateSamples + " 个同一片段重复样本，同一视频多人评分只算一个样本");
        }
        if (prep.pairs.isEmpty()) {
            warnings.add("当前范围没有可比较的配对，无法估计一致性");
        }
        return warnings;
    }

    private String severityCoverageNote(Prepared prep) {
        Map<String, Integer> bands = bandCoverage(prep);
        StringBuilder sb = new StringBuilder("严重度覆盖：");
        sb.append("MINOR=").append(bands.getOrDefault("MINOR", 0)).append("，")
                .append("MODERATE=").append(bands.getOrDefault("MODERATE", 0)).append("，")
                .append("SEVERE=").append(bands.getOrDefault("SEVERE", 0));
        if (bands.getOrDefault("SEVERE", 0) < SPARSE_THRESHOLD) {
            sb.append("；SEVERE 层样本不足，禁止声称对严重结构问题一致");
        }
        return sb.toString();
    }

    private String coverageNote(Prepared prep) {
        StringBuilder sb = new StringBuilder();
        sb.append("方法=").append(prep.method).append("；规程范围=").append(prep.scope)
                .append("；样本=").append(prep.samples.size())
                .append("，其中具独立盲评配对的样本=").append(prep.independentSampleCount)
                .append("；配对观察=").append(prep.pairCount()).append("。");
        sb.append("配对基准=留一法同侪共识（同规程、同缺陷类型、同样本内比较），已抵消样本自身难度；");
        sb.append("跨规程判读与非独立盲评记录不参与计算。");
        if ("DESIGNED".equals(prep.method)) {
            sb.append("覆盖边界：分层设计样本精度高但更新慢、复核成本高，仅代表抽样层，不能外推到未抽样管段。");
        } else if ("NATURAL".equals(prep.method)) {
            sb.append("覆盖边界：自然重叠样本覆盖真实案件更广，但受各承包商负责区域难度差异与复检选择偏差影响，"
                    + "需结合区域分层解读。");
        } else {
            sb.append("覆盖边界：混合两类样本，已按片段去重（设计样本优先），跨方法不外推。");
        }
        return sb.toString();
    }

    /** 单条更正直接影响的批次（供更正接口即时回显）。 */
    private String affectedBatchesOfReader(Long readerId, Long sourceBatchId) {
        Set<String> codes = new LinkedHashSet<>();
        String vendor = null;
        for (ConsistencyReader r : repo.findAllReaders()) {
            if (r.id().equals(readerId)) {
                vendor = r.vendorName();
            }
        }
        for (ContractorBatch b : inspectionRepo.findAllBatches()) {
            boolean hit = (sourceBatchId != null && b.id().equals(sourceBatchId))
                    || (vendor != null && vendor.equals(b.contractor()));
            if (hit) {
                codes.add(b.batchCode());
            }
        }
        return codes.isEmpty() ? null : String.join("，", codes);
    }

    /** 自上次画像以来发生更正的受影响批次。 */
    private String affectedBatchesSince(LocalDateTime since, Set<Long> readerIds) {
        if (readerIds.isEmpty()) {
            return null;
        }
        Set<String> batchCodes = new LinkedHashSet<>();
        Map<Long, String> vendorByReader = new HashMap<>();
        for (ConsistencyReader r : repo.findAllReaders()) {
            vendorByReader.put(r.id(), r.vendorName());
        }
        List<ContractorBatch> allBatches = inspectionRepo.findAllBatches();
        Set<Long> correctedReaders = new HashSet<>();
        Set<Long> correctedBatches = new HashSet<>();
        for (ConsistencyReading r : repo.findAllReadings()) {
            if (r.revisionNo() == null || r.revisionNo() < 2) {
                continue;
            }
            if (!readerIds.contains(r.readerId())) {
                continue;
            }
            if (since != null && r.submittedAt().isBefore(since)) {
                continue;
            }
            correctedReaders.add(r.readerId());
            if (r.sourceBatchId() != null) {
                correctedBatches.add(r.sourceBatchId());
            }
        }
        for (ContractorBatch b : allBatches) {
            boolean readerHit = b.contractor() != null
                    && correctedReaders.stream().anyMatch(id -> b.contractor().equals(vendorByReader.get(id)));
            if (correctedBatches.contains(b.id()) || readerHit) {
                batchCodes.add(b.batchCode());
            }
        }
        return batchCodes.isEmpty() ? null : String.join("，", batchCodes);
    }

    /** 供测试与接口复用的样本有效分层等级。 */
    public int effectiveBandGradeFor(Long sampleId) {
        ConsistencySample s = sample(sampleId);
        List<ConsistencyReading> eligible = repo.findReadingsBySample(sampleId).stream()
                .filter(ConsistencyReading::active).filter(r -> exclusionReason(r, s) == null).toList();
        return effectiveBandGrade(s, eligible);
    }

    // ================================================================ 统计工具
    private static double mean(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (int v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    static int clampBand(long grade) {
        if (grade < 1) {
            return 1;
        }
        if (grade > 4) {
            return 4;
        }
        return (int) grade;
    }

    static BigDecimal round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private static final class Stats {
        int distinctSamples;
        double meanBias;
        double biasLow;
        double biasHigh;
        double disagreement;
        double agreement;
        double agreementLow;
        double agreementHigh;
        double withinOne;
        int stricter;
        int looser;

        static Stats of(List<PairObs> pairs) {
            Stats s = new Stats();
            Set<Long> samples = new HashSet<>();
            List<Double> diffs = new ArrayList<>();
            List<Double> agree = new ArrayList<>();
            int within = 0;
            for (PairObs p : pairs) {
                samples.add(p.sampleId);
                double d = p.observed - p.peerMean;
                diffs.add(d);
                agree.add(Math.abs(d) < 0.0001 ? 1.0 : 0.0);
                if (Math.abs(d) <= 1.0001) {
                    within++;
                }
                if (d > 0) {
                    s.stricter++;
                } else if (d < 0) {
                    s.looser++;
                }
            }
            s.distinctSamples = samples.size();
            s.meanBias = diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            s.disagreement = diffs.stream().mapToDouble(Math::abs).average().orElse(0);
            s.agreement = agree.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            s.withinOne = pairs.isEmpty() ? 0 : (double) within / pairs.size();
            long seed = 17L * samples.hashCode() + pairs.size();
            double[] biasCi = bootstrap(diffs, meanStat, new Random(seed));
            s.biasLow = biasCi[0];
            s.biasHigh = biasCi[1];
            double[] agrCi = bootstrap(agree, meanStat, new Random(seed + 1));
            s.agreementLow = agrCi[0];
            s.agreementHigh = agrCi[1];
            return s;
        }

        private static final java.util.function.ToDoubleFunction<List<Double>> meanStat =
                list -> list.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        private static double[] bootstrap(List<Double> values,
                                          java.util.function.ToDoubleFunction<List<Double>> stat,
                                          Random random) {
            if (values.isEmpty()) {
                return new double[]{0, 0};
            }
            if (values.size() == 1) {
                double v = stat.applyAsDouble(values);
                return new double[]{v, v};
            }
            double[] samples = new double[BOOTSTRAP_ROUNDS];
            List<Double> resample = new ArrayList<>(values.size());
            for (int i = 0; i < BOOTSTRAP_ROUNDS; i++) {
                resample.clear();
                for (int j = 0; j < values.size(); j++) {
                    resample.add(values.get(random.nextInt(values.size())));
                }
                samples[i] = stat.applyAsDouble(resample);
            }
            java.util.Arrays.sort(samples);
            int low = (int) Math.floor(0.025 * (BOOTSTRAP_ROUNDS - 1));
            int high = (int) Math.ceil(0.975 * (BOOTSTRAP_ROUNDS - 1));
            return new double[]{samples[low], samples[high]};
        }
    }
}
