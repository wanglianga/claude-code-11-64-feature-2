package com.ccdev.drainage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class Requests {

    private Requests() {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record BatchUploadRequest(@NotBlank String batchCode, @NotBlank String contractor,
                                     @NotNull LocalDateTime inspectedAt, @NotBlank String gradingStandard,
                                     @NotBlank String directionConvention, @NotBlank String contentHash) {
    }

    public record VideoUploadRequest(@NotNull Long batchId, @NotBlank String videoCode,
                                     @NotBlank String fileUri, @NotBlank String segmentHint,
                                     @NotNull BigDecimal startM, @NotNull BigDecimal endM,
                                     @NotBlank String stationDirection, @NotBlank String contentHash) {
    }

    public record ObservationUploadRequest(@NotNull Long videoId, @NotBlank String defectType,
                                           @NotBlank String rawGrade, @NotNull BigDecimal stationStartM,
                                           @NotNull BigDecimal stationEndM, String evidenceUri,
                                           @NotBlank String evidenceStrength) {
    }

    public record DatasetGenerateRequest(@NotNull Long versionId, @NotBlank String scoringMode) {
    }

    public record AdjudicateRequest(@NotNull Integer resolvedGrade, @NotBlank String note) {
    }

    // ------------------------------------------------------------ 判读一致性域
    /** 分层抽取共同样本：按规程版本 + 严重度层各抽若干条。 */
    public record SampleDrawRequest(@NotBlank String standardCode,
                                    java.util.Map<String, Integer> perBandQuota,
                                    java.util.List<Long> readerIds,
                                    String note) {
    }

    public record ReadingSubmitRequest(@NotNull Long sampleId, @NotNull Long readerId,
                                       @NotBlank String rawGrade, Boolean blind,
                                       Boolean independent, Long seenPriorReadingId,
                                       Long sourceBatchId, String note) {
    }

    /** 承包商更正原判读：生成新版本，旧记录保留并标记已被取代。 */
    public record ReadingCorrectRequest(@NotBlank String rawGrade, @NotBlank String reason) {
    }

    /** 发布质量画像：method = DESIGNED / NATURAL / ALL。 */
    public record ProfilePublishRequest(@NotBlank String method, String standardScope,
                                        String revisionReason) {
    }
}
