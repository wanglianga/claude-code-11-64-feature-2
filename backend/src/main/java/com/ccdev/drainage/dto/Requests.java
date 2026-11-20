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
}
