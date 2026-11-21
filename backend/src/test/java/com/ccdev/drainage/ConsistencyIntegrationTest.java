package com.ccdev.drainage;

import com.ccdev.drainage.config.ApiException;
import com.ccdev.drainage.domain.Models.ConsistencyProfile;
import com.ccdev.drainage.domain.Models.ConsistencyProfileMetric;
import com.ccdev.drainage.domain.Models.ConsistencyReading;
import com.ccdev.drainage.domain.Models.ConsistencySample;
import com.ccdev.drainage.domain.Models.Observation;
import com.ccdev.drainage.repo.ConsistencyRepository;
import com.ccdev.drainage.repo.InspectionRepository;
import com.ccdev.drainage.service.AlignmentService;
import com.ccdev.drainage.service.ClusteringService;
import com.ccdev.drainage.service.ConsistencyService;
import com.ccdev.drainage.service.DatasetService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 承包商判读一致性服务集成测试：在独立库 ccdev_test 上重建 schema/种子，
 * 覆盖严重度不均衡、空间难度不均、非盲复核、规程切换、单家样本稀少、
 * 重复视频不重复计数、更正版本链与画像版本化。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConsistencyIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ConsistencyService consistencyService;
    @Autowired ConsistencyRepository consistencyRepo;
    @Autowired AlignmentService alignmentService;
    @Autowired ClusteringService clusteringService;
    @Autowired DatasetService datasetService;
    @Autowired InspectionRepository inspectionRepo;

    static {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/ccdev_test", "ccdev", "ccdev")) {
            try (var st = conn.createStatement()) {
                st.execute("DROP SCHEMA public CASCADE");
                st.execute("CREATE SCHEMA public");
            }
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/schema.sql"));
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("db/seed-data.sql"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void rebuildPipeline() {
        alignmentService.realignAll();
        clusteringService.rebuildClusters();
        datasetService.generateForVersion(2L, "MAX_SEVERITY", 1L);
    }

    private ConsistencySample sample(String code) {
        return consistencyRepo.findSampleByCode(code)
                .orElseThrow(() -> new IllegalStateException("样本不存在: " + code));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summary(Map<String, Object> analysis) {
        return (Map<String, Object>) analysis.get("summary");
    }

    @SuppressWarnings("unchecked")
    private List<String> warnings(Map<String, Object> analysis) {
        return (List<String>) analysis.get("warnings");
    }

    // ------------------------------------------------------------ 1. 严重度不均衡
    @Test
    @Order(1)
    void designedSamplesWithoutSevereBandCannotClaimSevereAgreement() {
        Map<String, Object> analysis = consistencyService.analysis("DESIGNED", null);
        Map<String, Object> coverage = consistencyService.coverage("DESIGNED", null);
        @SuppressWarnings("unchecked")
        Map<String, Integer> bands = (Map<String, Integer>) coverage.get("bandCoverage");
        assertEquals(0, bands.get("SEVERE"), "设计分层样例不含严重缺陷");
        assertTrue(bands.get("MINOR") > 0 && bands.get("MODERATE") > 0);
        String joined = String.join(" ", warnings(analysis));
        assertTrue(joined.contains("未覆盖 SEVERE"), "必须明确提示不得声称对严重结构问题一致: " + joined);
        assertTrue(joined.contains("不得外推"), "必须提示轻微/中等结论不得外推到严重缺陷: " + joined);
    }

    // ------------------------------------------------------------ 2. 非盲复核
    @Test
    @Order(2)
    void nonBlindReadingIsExcludedFromConsistencyEvidence() {
        // 样本 DS-002 的 R-REV2(#8) 是看到首评 #5 后打分，非盲且非独立
        Map<String, Object> evidence = consistencyService.evidence(sample("DS-002").id());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) evidence.get("pairs");
        assertTrue(pairs.stream().noneMatch(p -> Long.valueOf(5L).equals(p.get("readerId"))),
                "非独立盲评判读不得进入配对");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> excluded = (List<Map<String, Object>>) evidence.get("excludedReadings");
        assertEquals(1, excluded.size());
        assertTrue(String.valueOf(excluded.get(0).get("reason")).contains("非盲"));
        assertEquals(3, evidence.get("eligibleReadingCount"));

        Map<String, Object> analysis = consistencyService.analysis("ALL", "STD-CCT-2021");
        assertTrue(((Number) summary(analysis).get("excludedNonBlind")).intValue() >= 1);
    }

    // ------------------------------------------------------------ 3. 规程切换
    @Test
    @Order(3)
    void crossStandardReadingsAreExcludedAndNeverMixed() {
        // DS-004 的 R-BING(#15) 按 DX 规程判读 CCT 样本，必须排除
        Map<String, Object> evidence = consistencyService.evidence(sample("DS-004").id());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> excluded = (List<Map<String, Object>>) evidence.get("excludedReadings");
        assertTrue(excluded.stream().anyMatch(e -> String.valueOf(e.get("reason")).contains("跨规程")));

        // NS-004 属于 DX 规程层，CCT 判读 #29 不得混入
        Map<String, Object> dxEvidence = consistencyService.evidence(sample("NS-004").id());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dxPairs = (List<Map<String, Object>>) dxEvidence.get("pairs");
        assertTrue(dxPairs.stream().allMatch(p -> "STD-DX-2023".equals(p.get("standardCode"))),
                "DX 样本层只能包含 DX 规程判读");

        Map<String, Object> all = consistencyService.analysis("ALL", null);
        assertTrue(((Number) summary(all).get("excludedCrossStandard")).intValue() >= 3);
        // 分层键必须带规程版本
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strata = (List<Map<String, Object>>) all.get("strata");
        assertTrue(strata.stream().allMatch(s -> s.get("standardCode") != null));
    }

    // ------------------------------------------------------------ 4. 重复视频不重复计数
    @Test
    @Order(4)
    void sameClipScoredByManyReadersCountsOnceAndDuplicateClipMerged() {
        // DS-003 与 NS-001 指向同一物理片段（WS-001 30-31 LEAG），计算层只保留一个样本
        Map<String, Object> all = consistencyService.analysis("ALL", null);
        assertTrue(((Number) summary(all).get("mergedDuplicateSamples")).intValue() >= 1,
                "同一片段的设计/自然样本必须合并，不能当作多个独立管段样本");

        // 多人对同一样本评分只增加判读：DS-001 有 4 位判读方，但样本仍为 1
        Map<String, Object> evidence = consistencyService.evidence(sample("DS-001").id());
        assertEquals(4, evidence.get("eligibleReadingCount"));
        ConsistencySample ds001 = sample("DS-001");
        Long sameClipSamples = jdbc.queryForObject(
                "SELECT count(*) FROM app_consistency_sample WHERE canonical_key = ?",
                Long.class, ds001.canonicalKey());
        assertEquals(1L, sameClipSamples, "同一片段在样本库中只应有一条登记归一键");
    }

    // ------------------------------------------------------------ 5. 空间难度不均
    @Test
    @Order(5)
    void naturalOverlapExposesRegionStratification() {
        consistencyService.detectNaturalOverlaps(1L);
        Map<String, Object> natural = consistencyService.analysis("NATURAL", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> strata = (List<Map<String, Object>>) natural.get("strata");
        assertTrue(strata.stream().anyMatch(s -> "AREA-B".equals(s.get("regionCode"))),
                "自然重叠必须给出区域分层，暴露空间难度差异");
        String joined = String.join(" ", warnings(natural));
        assertTrue(joined.contains("区域分布不均"), "必须提示区域难度与复检选择偏差: " + joined);

        // 自然识别再次运行不重复建样本
        int firstCreated = (int) consistencyService.detectNaturalOverlaps(1L).get("createdSamples");
        assertEquals(0, firstCreated, "重复识别不得重复登记同一片段");
    }

    // ------------------------------------------------------------ 6. 单家样本稀少
    @Test
    @Order(6)
    void sparseSingleContractorIsFlaggedNotConcluded() {
        Map<String, Object> publish = consistencyService.publishProfile("DESIGNED", "ALL", null, 1L);
        String sparse = String.valueOf(publish.get("sparseReaderCodes"));
        assertTrue(sparse.contains("R-BING"), "单家稀少样本必须被标记: " + sparse);

        List<ConsistencyProfileMetric> metrics = consistencyService.profileMetrics(
                ((Number) publish.get("profileId")).longValue());
        assertFalse(metrics.isEmpty());
        assertTrue(metrics.stream().anyMatch(m -> "SPARSE".equals(m.metricStatus())),
                "样本低于阈值的判读方指标必须标记 SPARSE");
    }

    // ------------------------------------------------------------ 7. 更正版本链与画像版本化
    @Test
    @Order(7)
    void correctionCreatesNewVersionKeepsOldAndRepublishesProfile() {
        long originalProfileId = ((Number) consistencyService
                .publishProfile("DESIGNED", "ALL", null, 1L).get("profileId")).longValue();

        ConsistencyReading original = consistencyRepo.findActiveReading(sample("DS-002").id(), 1L).orElseThrow();
        int oldGrade = original.normalizedGrade();
        Map<String, Object> correction = consistencyService.correctReading(
                original.id(), "II", "复核帧后下调", 1L);
        long newReadingId = ((Number) correction.get("newReadingId")).longValue();

        ConsistencyReading superseded = consistencyRepo.findReadingById(original.id()).orElseThrow();
        assertFalse(superseded.active(), "旧判读下线但保留");
        assertEquals(newReadingId, superseded.supersededById());
        ConsistencyReading latest = consistencyRepo.findActiveReading(sample("DS-002").id(), 1L).orElseThrow();
        assertEquals(2, latest.revisionNo());
        assertEquals(2, latest.normalizedGrade());
        assertNotNull(correction.get("affectedBatches"));

        Map<String, Object> republish = consistencyService.publishProfile(
                "DESIGNED", "ALL", "甲公司更正判读", 1L);
        int version = ((Number) republish.get("versionNo")).intValue();
        assertTrue(version >= 2);
        assertEquals(originalProfileId, ((Number) republish.get("supersedesProfileId")).longValue());

        ConsistencyProfile oldProfile = consistencyService.profile(originalProfileId);
        assertEquals("SUPERSEDED", oldProfile.status());
        assertFalse(consistencyService.profileMetrics(originalProfileId).isEmpty(), "旧画像指标仍可查");
        assertNotNull(republish.get("affectedBatches"));
        assertTrue(String.valueOf(republish.get("affectedBatches")).contains("B-2025-01"));
        assertTrue(oldGrade != 2, "样例初值应为 III");
    }

    // ------------------------------------------------------------ 8. 不回写已发布等级
    @Test
    @Order(8)
    void profilePublishingNeverRewritesPublishedDefectGrades() {
        List<Observation> before = inspectionRepo.findAllObservations();
        consistencyService.publishProfile("ALL", "ALL", "回归校验", 1L);
        consistencyService.correctReading(
                consistencyRepo.findActiveReading(sample("DS-003").id(), 2L).orElseThrow().id(),
                "I", "复核更正", 1L);
        List<Observation> after = inspectionRepo.findAllObservations();
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).rawGrade(), after.get(i).rawGrade(),
                    "画像/一致性计算不得改写已发布观察等级");
            assertEquals(before.get(i).status(), after.get(i).status());
        }
        Long clusterGradeChanges = jdbc.queryForObject(
                "SELECT count(*) FROM app_defect_cluster WHERE status = 'CONFLICT'", Long.class);
        assertNotNull(clusterGradeChanges);
    }

    // ------------------------------------------------------------ 9. 录入与冲突
    @Test
    @Order(9)
    void duplicateActiveReadingRejectedButCorrectionAllowed() {
        Long sampleId = sample("DS-001").id();
        ApiException ex = assertThrows(ApiException.class, () -> consistencyService.submitReading(
                sampleId, 1L, "III", true, true, null, null, "重复录入", 1L));
        assertEquals(409, ex.getStatus());

        // 看到首评后打分的记录自动降级为非独立
        Map<String, Object> seen = consistencyService.submitReading(
                sample("DS-005").id(), 3L, "III", false, true, 16L, null, "看到首评", 1L);
        assertEquals(Boolean.FALSE, seen.get("blind"));
        assertEquals(false, seen.get("independent"));
        assertEquals(false, seen.get("eligibleAsEvidence"));
    }

    // ------------------------------------------------------------ 10. 画像方法隔离
    @Test
    @Order(10)
    void profileMethodAndStandardScopeAreIsolated() {
        Map<String, Object> designedCct = consistencyService.publishProfile("DESIGNED", "STD-CCT-2021", null, 1L);
        Map<String, Object> designedCct2 = consistencyService.publishProfile("DESIGNED", "STD-CCT-2021", null, 1L);
        assertTrue(((Number) designedCct2.get("versionNo")).intValue()
                > ((Number) designedCct.get("versionNo")).intValue());

        List<ConsistencyProfileMetric> metrics = consistencyService.profileMetrics(
                ((Number) designedCct2.get("profileId")).longValue());
        assertTrue(metrics.stream().allMatch(m -> "STD-CCT-2021".equals(m.standardCode())),
                "限定规程范围的画像不得包含其他规程指标");
        // 有 2 个严重度层以上的样本，画像能给出配对
        assertTrue(metrics.stream().anyMatch(m -> m.pairedN() > 0));
        ConsistencyProfile profile = consistencyService.profile(((Number) designedCct2.get("profileId")).longValue());
        assertTrue(profile.coverageNote().contains("覆盖边界"));
    }
}
