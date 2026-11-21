package com.ccdev.drainage.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领域模型集合：全部以不可变 record 承载，数据库写入通过 JdbcTemplate 显式 SQL 完成。
 */
public final class Models {

    private Models() {
    }

    public record AssetVersion(Long id, String versionNo, String name, LocalDateTime effectiveAt,
                               String status, String note) {
    }

    public record Manhole(Long id, Long versionId, String manholeCode, BigDecimal geomX, BigDecimal geomY) {
    }

    public record PipeSegment(Long id, Long versionId, String segmentCode, Long upstreamMhId,
                              Long downstreamMhId, BigDecimal lengthM, String flowDirection,
                              String material, Integer diameterMm, Long parentSegmentId,
                              String lifecycleStatus) {
    }

    public record SegmentMapping(Long id, Long oldSegmentId, Long newSegmentId,
                                 BigDecimal oldStartM, BigDecimal oldEndM,
                                 BigDecimal newStartM, BigDecimal newEndM,
                                 String basis, String note) {
    }

    public record ContractorBatch(Long id, String batchCode, String contractor, LocalDateTime inspectedAt,
                                  String gradingStandard, String directionConvention, String contentHash) {
    }

    public record Video(Long id, Long batchId, String videoCode, String fileUri, String segmentHint,
                        BigDecimal startM, BigDecimal endM, String stationDirection,
                        String contentHash, Long duplicateOfId) {
    }

    public record Observation(Long id, Long videoId, Long batchId, String defectType, String rawGrade,
                              BigDecimal stationStartM, BigDecimal stationEndM, String evidenceUri,
                              String evidenceStrength, String status, Long duplicateOfId, Long clusterId) {
    }

    public record JudgementRule(String defectType, Integer minGrade, BigDecimal minLengthM,
                                String requiredEvidence, Boolean enabled) {
    }

    public record AlignmentCandidate(Long id, Long observationId, Long segmentId, Long assetVersionId,
                                     BigDecimal normStartM, BigDecimal normEndM, BigDecimal score,
                                     Boolean selected, String reason) {
    }

    public record Attribution(Long observationId, Long segmentId, Long assetVersionId,
                              BigDecimal normStartM, BigDecimal normEndM, LocalDateTime fixedAt, String note) {
    }

    public record DefectCluster(Long id, String clusterCode, Long segmentId, Long assetVersionId,
                                String defectType, BigDecimal normStartM, BigDecimal normEndM,
                                String status, Boolean conflictFlag, Integer resolvedGrade,
                                String resolutionNote, Long resolvedBy, LocalDateTime resolvedAt) {
    }

    public record ClusterMember(Long id, Long clusterId, Long observationId, String memberRole,
                                Integer gradeAtInspection, String evidenceStrength, Long batchId) {
    }

    public record Dataset(Long id, String datasetCode, Long asOfVersionId, String scoringMode,
                          String status, LocalDateTime generatedAt, Long generatedBy, String formulaNote) {
    }

    public record SegmentScore(Long id, Long datasetId, Long segmentId, Integer maxGrade,
                               BigDecimal burdenScore, Integer rankNo, Boolean rankingPaused,
                               String pauseReasons, Integer eligibleClusterCount, Integer conflictCount,
                               BigDecimal evidenceCompleteness, String explanation) {
    }

    public record DatasetImpact(Long id, Long datasetId, Long oldSegmentId, Long newSegmentId,
                                String impactType, Integer inheritedCount, Integer quarantinedCount,
                                String note) {
    }

    public record AppUser(Long id, String username, String passwordHash, String displayName, String role) {
    }

    public record AuditLog(Long id, Long userId, String action, String targetRef, String detail,
                           LocalDateTime createdAt) {
    }

    // ------------------------------------------------------------ 判读一致性域
    public record ConsistencyReader(Long id, String readerCode, String readerName, String readerType,
                                    String vendorName, String note) {
    }

    public record ConsistencySample(Long id, String sampleCode, String sourceType, String standardCode,
                                    String defectType, String severityBand, String regionCode,
                                    String difficultyBand, Long videoId, Long canonicalVideoId,
                                    String canonicalKey, String segmentCode,
                                    BigDecimal clipStartM, BigDecimal clipEndM,
                                    Integer referenceGrade, String referenceSource, String status,
                                    String originRef, String samplingNote, Long createdBy,
                                    LocalDateTime createdAt) {
    }

    public record ConsistencyAssignment(Long id, Long sampleId, Long readerId, String blindIdentity,
                                        String status, LocalDateTime assignedAt,
                                        LocalDateTime submittedAt) {
    }

    public record ConsistencyReading(Long id, Long sampleId, Long readerId, Long assignmentId,
                                     Long sourceBatchId, String blindIdentity, String standardCode,
                                     String rawGrade, Integer normalizedGrade, Boolean blind,
                                     Boolean independent, Long seenPriorReadingId, Boolean active,
                                     Integer revisionNo, Long revisionOfId, Long supersededById,
                                     String correctionReason, String note,
                                     LocalDateTime submittedAt) {
    }

    public record ConsistencyProfile(Long id, String profileCode, String method, String standardScope,
                                     Integer versionNo, String status, Integer sampleCount,
                                     Integer independentSampleCount, Integer readingCount,
                                     Integer pairedObservationCount, Integer excludedNonBlind,
                                     Integer excludedCrossStandard, String sparseReaderCodes,
                                     String severityCoverageNote, String coverageNote,
                                     String revisionReason, String affectedBatches,
                                     Long supersedesProfileId, LocalDateTime generatedAt,
                                     Long generatedBy) {
    }

    public record ConsistencyProfileMetric(Long id, Long profileId, Long readerId, String standardCode,
                                           String defectType, String severityBand, String regionCode,
                                           Integer pairedN, Integer distinctSampleN,
                                           BigDecimal meanBias, BigDecimal biasCiLow,
                                           BigDecimal biasCiHigh, BigDecimal disagreementIndex,
                                           BigDecimal agreementRate, BigDecimal agreementCiLow,
                                           BigDecimal agreementCiHigh, BigDecimal withinOneRate,
                                           Integer stricterCount, Integer looserCount,
                                           String metricStatus, String note) {
    }
}
