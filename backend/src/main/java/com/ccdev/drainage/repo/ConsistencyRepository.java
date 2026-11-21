package com.ccdev.drainage.repo;

import com.ccdev.drainage.domain.Models.ConsistencyAssignment;
import com.ccdev.drainage.domain.Models.ConsistencyProfile;
import com.ccdev.drainage.domain.Models.ConsistencyProfileMetric;
import com.ccdev.drainage.domain.Models.ConsistencyReader;
import com.ccdev.drainage.domain.Models.ConsistencyReading;
import com.ccdev.drainage.domain.Models.ConsistencySample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 判读一致性域仓储：样本、盲评任务、判读记录（含更正版本链）、质量画像版本与指标。
 */
@Repository
public class ConsistencyRepository {

    private final JdbcTemplate jdbc;

    public ConsistencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ConsistencyReader> READER_MAPPER = (rs, n) -> new ConsistencyReader(
            rs.getLong("id"), rs.getString("reader_code"), rs.getString("reader_name"),
            rs.getString("reader_type"), rs.getString("vendor_name"), rs.getString("note"));

    private static final RowMapper<ConsistencySample> SAMPLE_MAPPER = (rs, n) -> new ConsistencySample(
            rs.getLong("id"), rs.getString("sample_code"), rs.getString("source_type"),
            rs.getString("standard_code"), rs.getString("defect_type"), rs.getString("severity_band"),
            rs.getString("region_code"), rs.getString("difficulty_band"),
            rs.getObject("video_id", Long.class), rs.getObject("canonical_video_id", Long.class),
            rs.getString("canonical_key"), rs.getString("segment_code"),
            rs.getBigDecimal("clip_start_m"), rs.getBigDecimal("clip_end_m"),
            rs.getObject("reference_grade", Integer.class), rs.getString("reference_source"),
            rs.getString("status"), rs.getString("origin_ref"), rs.getString("sampling_note"),
            rs.getObject("created_by", Long.class),
            rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<ConsistencyAssignment> ASSIGNMENT_MAPPER = (rs, n) -> new ConsistencyAssignment(
            rs.getLong("id"), rs.getLong("sample_id"), rs.getLong("reader_id"),
            rs.getString("blind_identity"), rs.getString("status"),
            rs.getTimestamp("assigned_at").toLocalDateTime(),
            rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toLocalDateTime());

    private static final RowMapper<ConsistencyReading> READING_MAPPER = (rs, n) -> new ConsistencyReading(
            rs.getLong("id"), rs.getLong("sample_id"), rs.getLong("reader_id"),
            rs.getObject("assignment_id", Long.class), rs.getObject("source_batch_id", Long.class),
            rs.getString("blind_identity"), rs.getString("standard_code"), rs.getString("raw_grade"),
            rs.getInt("normalized_grade"), rs.getBoolean("blind"), rs.getBoolean("independent"),
            rs.getObject("seen_prior_reading_id", Long.class), rs.getBoolean("active"),
            rs.getInt("revision_no"), rs.getObject("revision_of_id", Long.class),
            rs.getObject("superseded_by_id", Long.class), rs.getString("correction_reason"),
            rs.getString("note"), rs.getTimestamp("submitted_at").toLocalDateTime());

    private static final RowMapper<ConsistencyProfile> PROFILE_MAPPER = (rs, n) -> new ConsistencyProfile(
            rs.getLong("id"), rs.getString("profile_code"), rs.getString("method"),
            rs.getString("standard_scope"), rs.getInt("version_no"), rs.getString("status"),
            rs.getInt("sample_count"), rs.getInt("independent_sample_count"),
            rs.getInt("reading_count"), rs.getInt("paired_observation_count"),
            rs.getInt("excluded_non_blind"), rs.getInt("excluded_cross_standard"),
            rs.getString("sparse_reader_codes"), rs.getString("severity_coverage_note"),
            rs.getString("coverage_note"), rs.getString("revision_reason"),
            rs.getString("affected_batches"), rs.getObject("supersedes_profile_id", Long.class),
            rs.getTimestamp("generated_at").toLocalDateTime(),
            rs.getObject("generated_by", Long.class));

    private static final RowMapper<ConsistencyProfileMetric> METRIC_MAPPER = (rs, n) -> new ConsistencyProfileMetric(
            rs.getLong("id"), rs.getLong("profile_id"), rs.getLong("reader_id"),
            rs.getString("standard_code"), rs.getString("defect_type"), rs.getString("severity_band"),
            rs.getString("region_code"), rs.getInt("paired_n"), rs.getInt("distinct_sample_n"),
            rs.getBigDecimal("mean_bias"), rs.getBigDecimal("bias_ci_low"), rs.getBigDecimal("bias_ci_high"),
            rs.getBigDecimal("disagreement_index"), rs.getBigDecimal("agreement_rate"),
            rs.getBigDecimal("agreement_ci_low"), rs.getBigDecimal("agreement_ci_high"),
            rs.getBigDecimal("within_one_rate"), rs.getInt("stricter_count"), rs.getInt("looser_count"),
            rs.getString("metric_status"), rs.getString("note"));

    // ------------------------------------------------------------ 判读方
    public List<ConsistencyReader> findAllReaders() {
        return jdbc.query("SELECT * FROM app_consistency_reader ORDER BY id", READER_MAPPER);
    }

    public Optional<ConsistencyReader> findReaderById(Long id) {
        return jdbc.query("SELECT * FROM app_consistency_reader WHERE id = ?", READER_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ConsistencyReader> findReaderByCode(String code) {
        return jdbc.query("SELECT * FROM app_consistency_reader WHERE reader_code = ?", READER_MAPPER, code)
                .stream().findFirst();
    }

    public long insertReader(String code, String name, String type, String vendor, String note) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_consistency_reader(reader_code, reader_name, reader_type, vendor_name, note) "
                            + "VALUES (?,?,?,?,?) ON CONFLICT (reader_code) DO NOTHING",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            ps.setString(3, type);
            ps.setString(4, vendor);
            ps.setString(5, note);
            return ps;
        }, kh);
        if (kh.getKeys() != null && kh.getKeys().get("id") != null) {
            return ((Number) kh.getKeys().get("id")).longValue();
        }
        return findReaderByCode(code).orElseThrow().id();
    }

    // ------------------------------------------------------------ 共同样本
    public List<ConsistencySample> findAllSamples() {
        return jdbc.query("SELECT * FROM app_consistency_sample ORDER BY id", SAMPLE_MAPPER);
    }

    public Optional<ConsistencySample> findSampleById(Long id) {
        return jdbc.query("SELECT * FROM app_consistency_sample WHERE id = ?", SAMPLE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ConsistencySample> findSampleByCode(String code) {
        return jdbc.query("SELECT * FROM app_consistency_sample WHERE sample_code = ?", SAMPLE_MAPPER, code)
                .stream().findFirst();
    }

    public Optional<ConsistencySample> findByCanonicalKey(String key) {
        return jdbc.query("SELECT * FROM app_consistency_sample WHERE canonical_key = ?", SAMPLE_MAPPER, key)
                .stream().findFirst();
    }

    /** 同一视频同一片段同一规程同一缺陷类型只建一个样本。 */
    public boolean existsClip(Long canonicalVideoId, BigDecimal start, BigDecimal end,
                              String defectType, String standardCode) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app_consistency_sample WHERE canonical_video_id = ? "
                        + "AND clip_start_m = ? AND clip_end_m = ? AND defect_type = ? AND standard_code = ?",
                Long.class, canonicalVideoId, start, end, defectType, standardCode);
        return count != null && count > 0;
    }

    public long insertSample(ConsistencySample s) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_consistency_sample(sample_code, source_type, standard_code, defect_type, "
                            + "severity_band, region_code, difficulty_band, video_id, canonical_video_id, "
                            + "canonical_key, segment_code, clip_start_m, clip_end_m, reference_grade, "
                            + "reference_source, status, origin_ref, sampling_note, created_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT DO NOTHING",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, s.sampleCode());
            ps.setString(2, s.sourceType());
            ps.setString(3, s.standardCode());
            ps.setString(4, s.defectType());
            ps.setString(5, s.severityBand());
            ps.setString(6, s.regionCode());
            ps.setString(7, s.difficultyBand());
            setNullableLong(ps, 8, s.videoId());
            setNullableLong(ps, 9, s.canonicalVideoId());
            ps.setString(10, s.canonicalKey());
            ps.setString(11, s.segmentCode());
            ps.setBigDecimal(12, s.clipStartM());
            ps.setBigDecimal(13, s.clipEndM());
            if (s.referenceGrade() == null) {
                ps.setNull(14, java.sql.Types.INTEGER);
            } else {
                ps.setInt(14, s.referenceGrade());
            }
            ps.setString(15, s.referenceSource());
            ps.setString(16, s.status());
            ps.setString(17, s.originRef());
            ps.setString(18, s.samplingNote());
            setNullableLong(ps, 19, s.createdBy());
            return ps;
        }, kh);
        if (kh.getKeys() != null && kh.getKeys().get("id") != null) {
            return ((Number) kh.getKeys().get("id")).longValue();
        }
        return findByCanonicalKey(s.canonicalKey()).orElseThrow().id();
    }

    public void closeSample(Long sampleId) {
        jdbc.update("UPDATE app_consistency_sample SET status = 'CLOSED' WHERE id = ?", sampleId);
    }

    // ------------------------------------------------------------ 盲评任务
    public long insertAssignment(Long sampleId, Long readerId, String blindIdentity) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_consistency_assignment(sample_id, reader_id, blind_identity) VALUES (?,?,?) "
                            + "ON CONFLICT (sample_id, reader_id) DO NOTHING",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sampleId);
            ps.setLong(2, readerId);
            ps.setString(3, blindIdentity);
            return ps;
        }, kh);
        if (kh.getKeys() != null && kh.getKeys().get("id") != null) {
            return ((Number) kh.getKeys().get("id")).longValue();
        }
        return findAssignmentsBySample(sampleId).stream()
                .filter(a -> a.readerId().equals(readerId)).findFirst().orElseThrow().id();
    }

    public List<ConsistencyAssignment> findAssignmentsBySample(Long sampleId) {
        return jdbc.query("SELECT * FROM app_consistency_assignment WHERE sample_id = ? ORDER BY id",
                ASSIGNMENT_MAPPER, sampleId);
    }

    public Optional<ConsistencyAssignment> findAssignment(Long sampleId, Long readerId) {
        return jdbc.query("SELECT * FROM app_consistency_assignment WHERE sample_id = ? AND reader_id = ?",
                ASSIGNMENT_MAPPER, sampleId, readerId).stream().findFirst();
    }

    public void markAssignmentSubmitted(Long assignmentId) {
        jdbc.update("UPDATE app_consistency_assignment SET status = 'SUBMITTED', submitted_at = now() "
                + "WHERE id = ?", assignmentId);
    }

    // ------------------------------------------------------------ 判读记录
    public List<ConsistencyReading> findAllReadings() {
        return jdbc.query("SELECT * FROM app_consistency_reading ORDER BY id", READING_MAPPER);
    }

    public List<ConsistencyReading> findReadingsBySample(Long sampleId) {
        return jdbc.query("SELECT * FROM app_consistency_reading WHERE sample_id = ? ORDER BY id",
                READING_MAPPER, sampleId);
    }

    public Optional<ConsistencyReading> findReadingById(Long id) {
        return jdbc.query("SELECT * FROM app_consistency_reading WHERE id = ?", READING_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ConsistencyReading> findActiveReading(Long sampleId, Long readerId) {
        return jdbc.query("SELECT * FROM app_consistency_reading WHERE sample_id = ? AND reader_id = ? AND active",
                READING_MAPPER, sampleId, readerId).stream().findFirst();
    }

    public long insertReading(ConsistencyReading r) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_consistency_reading(sample_id, reader_id, assignment_id, source_batch_id, "
                            + "blind_identity, standard_code, raw_grade, normalized_grade, blind, independent, "
                            + "seen_prior_reading_id, active, revision_no, revision_of_id, correction_reason, note) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.sampleId());
            ps.setLong(2, r.readerId());
            setNullableLong(ps, 3, r.assignmentId());
            setNullableLong(ps, 4, r.sourceBatchId());
            ps.setString(5, r.blindIdentity());
            ps.setString(6, r.standardCode());
            ps.setString(7, r.rawGrade());
            ps.setInt(8, r.normalizedGrade());
            ps.setBoolean(9, Boolean.TRUE.equals(r.blind()));
            ps.setBoolean(10, Boolean.TRUE.equals(r.independent()));
            setNullableLong(ps, 11, r.seenPriorReadingId());
            ps.setBoolean(12, Boolean.TRUE.equals(r.active()));
            ps.setInt(13, r.revisionNo() == null ? 1 : r.revisionNo());
            setNullableLong(ps, 14, r.revisionOfId());
            ps.setString(15, r.correctionReason());
            ps.setString(16, r.note());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    /** 更正：旧记录先下线，等新版本插入后再回填指向（部分唯一索引只允许一条 active）。 */
    public void deactivateReading(Long oldId) {
        jdbc.update("UPDATE app_consistency_reading SET active = false WHERE id = ?", oldId);
    }

    public void linkSuperseded(Long oldId, Long newId) {
        jdbc.update("UPDATE app_consistency_reading SET superseded_by_id = ? WHERE id = ?", newId, oldId);
    }

    // ------------------------------------------------------------ 质量画像
    public List<ConsistencyProfile> findAllProfiles() {
        return jdbc.query("SELECT * FROM app_consistency_profile ORDER BY id DESC", PROFILE_MAPPER);
    }

    public Optional<ConsistencyProfile> findProfileById(Long id) {
        return jdbc.query("SELECT * FROM app_consistency_profile WHERE id = ?", PROFILE_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<ConsistencyProfile> findLatestProfile(String method, String scope) {
        return jdbc.query("SELECT * FROM app_consistency_profile WHERE method = ? AND standard_scope = ? "
                        + "ORDER BY version_no DESC LIMIT 1", PROFILE_MAPPER, method, scope)
                .stream().findFirst();
    }

    public int nextProfileVersion(String method, String scope) {
        Integer max = jdbc.queryForObject("SELECT coalesce(max(version_no), 0) FROM app_consistency_profile "
                + "WHERE method = ? AND standard_scope = ?", Integer.class, method, scope);
        return (max == null ? 0 : max) + 1;
    }

    public void supersedeProfile(Long id) {
        jdbc.update("UPDATE app_consistency_profile SET status = 'SUPERSEDED' WHERE id = ?", id);
    }

    public long insertProfile(ConsistencyProfile p) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_consistency_profile(profile_code, method, standard_scope, version_no, status, "
                            + "sample_count, independent_sample_count, reading_count, paired_observation_count, "
                            + "excluded_non_blind, excluded_cross_standard, sparse_reader_codes, "
                            + "severity_coverage_note, coverage_note, revision_reason, affected_batches, "
                            + "supersedes_profile_id, generated_by) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, p.profileCode());
            ps.setString(2, p.method());
            ps.setString(3, p.standardScope());
            ps.setInt(4, p.versionNo());
            ps.setString(5, p.status());
            ps.setInt(6, p.sampleCount());
            ps.setInt(7, p.independentSampleCount());
            ps.setInt(8, p.readingCount());
            ps.setInt(9, p.pairedObservationCount());
            ps.setInt(10, p.excludedNonBlind());
            ps.setInt(11, p.excludedCrossStandard());
            ps.setString(12, p.sparseReaderCodes());
            ps.setString(13, p.severityCoverageNote());
            ps.setString(14, p.coverageNote());
            ps.setString(15, p.revisionReason());
            ps.setString(16, p.affectedBatches());
            setNullableLong(ps, 17, p.supersedesProfileId());
            setNullableLong(ps, 18, p.generatedBy());
            return ps;
        }, kh);
        return ((Number) kh.getKeys().get("id")).longValue();
    }

    public void insertMetric(Long profileId, ConsistencyProfileMetric m) {
        jdbc.update("INSERT INTO app_consistency_profile_metric(profile_id, reader_id, standard_code, "
                        + "defect_type, severity_band, region_code, paired_n, distinct_sample_n, mean_bias, "
                        + "bias_ci_low, bias_ci_high, disagreement_index, agreement_rate, agreement_ci_low, "
                        + "agreement_ci_high, within_one_rate, stricter_count, looser_count, metric_status, note) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                profileId, m.readerId(), m.standardCode(), m.defectType(), m.severityBand(),
                m.regionCode(), m.pairedN(), m.distinctSampleN(), m.meanBias(), m.biasCiLow(),
                m.biasCiHigh(), m.disagreementIndex(), m.agreementRate(), m.agreementCiLow(),
                m.agreementCiHigh(), m.withinOneRate(), m.stricterCount(), m.looserCount(),
                m.metricStatus(), m.note());
    }

    public List<ConsistencyProfileMetric> findMetricsByProfile(Long profileId) {
        return jdbc.query("SELECT * FROM app_consistency_profile_metric WHERE profile_id = ? "
                + "ORDER BY standard_code, defect_type, severity_band, region_code, reader_id",
                METRIC_MAPPER, profileId);
    }

    private static void setNullableLong(PreparedStatement ps, int idx, Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, java.sql.Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }

    public static Timestamp ts(java.time.LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }
}
