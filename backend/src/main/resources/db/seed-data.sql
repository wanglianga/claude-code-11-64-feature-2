-- ============================================================================
-- 虚构样例数据（可重复执行：ON CONFLICT DO NOTHING）
-- 场景覆盖：
--   * 反向里程批次（B-2025-02，从下游井起算）
--   * 两批独立检查对同一位置给出不相容等级（WS-002 接口错位 III vs D=4）
--   * 重复上传（VID-B3-WS001-DUP 与 V1 同 content_hash）与重叠片段（V6）
--   * 管段拆分（WS-003 → WS-003A/WS-003B，拆分点 22m；OBS-11 跨拆分点）
--   * 低等级/证据不足观察不入围（OBS-14 等级 I）
-- 对齐候选、聚类、数据集由后端启动任务计算，不在此手工铺设。
-- ============================================================================

-- ---------------------------------------------------------------- 用户
INSERT INTO app_user (id, username, password_hash, display_name, role) VALUES
 (1, 'admin',    '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', '工程处管理员', 'ADMIN'),
 (2, 'engineer', 'f63248efa4a61efc9f4c9f6e5de25b34b6f2b827717cd4c6b3905481c3bd483b', '复核工程师',   'ENGINEER')
ON CONFLICT (username) DO NOTHING;

-- ---------------------------------------------------------------- 资产版本
INSERT INTO app_asset_version (id, version_no, name, effective_at, status, note) VALUES
 (1, 'V2024-01', '2024 版竣工资产图', TIMESTAMP '2024-01-01 00:00:00', 'SUPERSEDED', '初始普查基线版本'),
 (2, 'V2026-06', '2026 年 6 月测绘修订版', TIMESTAMP '2026-06-01 00:00:00', 'ACTIVE', 'WS-003 复测后拆分为 WS-003A/WS-003B，新增井 MH03X')
ON CONFLICT (version_no) DO NOTHING;

-- ---------------------------------------------------------------- 井
INSERT INTO app_manhole (id, version_id, manhole_code, geom_x, geom_y) VALUES
 (1, 1, 'MH01', 100.000, 200.000),
 (2, 1, 'MH02', 160.000, 200.000),
 (3, 1, 'MH03', 210.000, 200.000),
 (4, 1, 'MH04', 250.000, 200.000),
 (5, 2, 'MH01', 100.000, 200.000),
 (6, 2, 'MH02', 160.000, 200.000),
 (7, 2, 'MH03', 210.000, 200.000),
 (8, 2, 'MH04', 250.000, 200.000),
 (9, 2, 'MH03X', 232.000, 200.000)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 管段
-- 旧版本三段
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (1, 1, 'WS-001', 1, 2, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (2, 1, 'WS-002', 2, 3, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, NULL, 'ACTIVE'),
 (3, 1, 'WS-003', 3, 4, 40.000, 'UP_TO_DOWN', 'PVC', 600, NULL, 'SPLIT')
ON CONFLICT DO NOTHING;
-- 新版本：WS-001/WS-002 延续，WS-003 拆分
INSERT INTO app_pipe_segment
 (id, version_id, segment_code, upstream_mh_id, downstream_mh_id, length_m, flow_direction, material, diameter_mm, parent_segment_id, lifecycle_status) VALUES
 (4, 2, 'WS-001', 5, 6, 60.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 1, 'ACTIVE'),
 (5, 2, 'WS-002', 6, 7, 50.000, 'UP_TO_DOWN', '钢筋混凝土', 800, 2, 'ACTIVE'),
 (6, 2, 'WS-003A', 7, 9, 22.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE'),
 (7, 2, 'WS-003B', 9, 8, 18.000, 'UP_TO_DOWN', 'PVC', 600, 3, 'ACTIVE')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 新旧管段里程映射（归一里程：上游井为 0）
INSERT INTO app_segment_mapping
 (id, old_segment_id, new_segment_id, old_start_m, old_end_m, new_start_m, new_end_m, basis, note) VALUES
 (1, 1, 4,  0.000, 60.000,  0.000, 60.000, 'STATION_EQUIVALENCE', 'WS-001 几何未变，里程等值映射'),
 (2, 2, 5,  0.000, 50.000,  0.000, 50.000, 'STATION_EQUIVALENCE', 'WS-002 几何未变，里程等值映射'),
 (3, 3, 6,  0.000, 22.000,  0.000, 22.000, 'SURVEY_MEASURE', 'WS-003 前段 → WS-003A，拆分点 22m'),
 (4, 3, 7, 22.000, 40.000,  0.000, 18.000, 'SURVEY_MEASURE', 'WS-003 后段 → WS-003B，新里程自 MH03X 起算')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 等级标准
INSERT INTO app_grade_standard (standard_code, raw_grade, normalized_grade) VALUES
 ('STD-CCT-2021', 'I',   1),
 ('STD-CCT-2021', 'II',  2),
 ('STD-CCT-2021', 'III', 3),
 ('STD-CCT-2021', 'IV',  4),
 ('STD-DX-2023',  'A',   1),
 ('STD-DX-2023',  'B',   2),
 ('STD-DX-2023',  'C',   3),
 ('STD-DX-2023',  'D',   4)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 判读规则（进入评估数据集的门槛）
INSERT INTO app_judgement_rule (defect_type, min_grade, min_length_m, required_evidence, enabled) VALUES
 ('CRACK',             2, 1.000, 'PARTIAL', true),
 ('DEFORMATION',       3, 0.000, 'SPARSE',  true),
 ('LEAK',              2, 0.000, 'SPARSE',  true),
 ('JOINT_DISLOCATION', 3, 0.000, 'PARTIAL', true)
ON CONFLICT (defect_type) DO NOTHING;

-- ---------------------------------------------------------------- 承包商批次
INSERT INTO app_contractor_batch (id, batch_code, contractor, inspected_at, grading_standard, direction_convention, content_hash) VALUES
 (1, 'B-2025-01', '甲勘测院',   TIMESTAMP '2025-03-10 09:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202501'),
 (2, 'B-2025-02', '乙检测公司', TIMESTAMP '2025-09-18 10:30:00', 'STD-DX-2023',  '下游井起算，反向里程（0 在下游井）', 'hash:batch:b202502'),
 (3, 'B-2026-03', '甲勘测院复测', TIMESTAMP '2026-03-05 14:00:00', 'STD-CCT-2021', '上游井起算，里程沿前进方向递增', 'hash:batch:b202603')
ON CONFLICT (batch_code) DO NOTHING;

-- ---------------------------------------------------------------- 视频索引
-- V5 与 V1 内容哈希相同：重复上传；V6 为部分重叠片段
INSERT INTO app_video (id, batch_id, video_code, file_uri, segment_hint, start_m, end_m, station_direction, content_hash, duplicate_of_id) VALUES
 (1, 1, 'VID-B1-WS001',     'archive://2025/B1/ws001.mp4',     'WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    NULL),
 (2, 2, 'VID-B2-WS001',     'archive://2025/B2/ws001-rev.mp4', 'WS-001',  0.000, 60.000, 'REVERSED', 'sha256:vid-ws001-rev',     NULL),
 (3, 1, 'VID-B1-WS002',     'archive://2025/B1/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002',         NULL),
 (4, 2, 'VID-B2-WS002',     'archive://2025/B2/ws002.mp4',     'WS-002',  0.000, 50.000, 'FORWARD',  'sha256:vid-ws002-b2',      NULL),
 (5, 3, 'VID-B3-WS001-DUP', 'archive://2026/B3/ws001-copy.mp4','WS-001',  0.000, 60.000, 'FORWARD',  'sha256:vid-ws001-full',    1),
 (6, 3, 'VID-B3-WS001-OVL', 'archive://2026/B3/ws001-part.mp4','WS-001',  8.000, 20.000, 'FORWARD',  'sha256:vid-ws001-partial', NULL),
 (7, 1, 'VID-B1-WS003',     'archive://2025/B1/ws003.mp4',     'WS-003',  0.000, 40.000, 'FORWARD',  'sha256:vid-ws003',         NULL),
 (8, 2, 'VID-B2-WS003',     'archive://2025/B2/ws003-rev.mp4', 'WS-003',  0.000, 40.000, 'REVERSED', 'sha256:vid-ws003-rev',     NULL)
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------- 观察
-- 归一里程由后端按视频方向换算（REVERSED: norm = length - station），此处仅存原始里程
INSERT INTO app_observation
 (id, video_id, batch_id, defect_type, raw_grade, station_start_m, station_end_m, evidence_uri, evidence_strength, status, duplicate_of_id) VALUES
-- B1 对 WS-001 的检查
 (1,  1, 1, 'CRACK',             'II',  10.000, 12.500, 'archive://2025/B1/frames/obs-001.zip', 'FULL',    'PENDING', NULL),
 (2,  1, 1, 'LEAK',              'III', 30.000, 31.000, 'archive://2025/B1/frames/obs-002.zip', 'PARTIAL', 'PENDING', NULL),
 (14, 1, 1, 'CRACK',             'I',   50.000, 51.000, 'archive://2025/B1/frames/obs-014.zip', 'SPARSE',  'PENDING', NULL),
-- B2 反向里程对 WS-001：47.5–50 反向 ⇒ 归一 10–12.5（与 OBS-1 同位置同等级，重复）；20–21 反向 ⇒ 归一 39–40
 (3,  2, 2, 'CRACK',             'B',   47.500, 50.000, 'archive://2025/B2/frames/obs-003.zip', 'FULL',    'PENDING', NULL),
 (4,  2, 2, 'DEFORMATION',       'D',   20.000, 21.000, 'archive://2025/B2/frames/obs-004.zip', 'SPARSE',  'PENDING', NULL),
-- WS-002 同位置等级不相容：III(FULL) vs D=4(PARTIAL)，证据不足以裁决 ⇒ 冲突暂停
 (5,  3, 1, 'JOINT_DISLOCATION', 'III', 15.000, 15.500, 'archive://2025/B1/frames/obs-005.zip', 'FULL',    'PENDING', NULL),
 (6,  4, 2, 'JOINT_DISLOCATION', 'D',   15.000, 15.600, 'archive://2025/B2/frames/obs-006.zip', 'PARTIAL', 'PENDING', NULL),
-- B3 重复上传视频 V5 中的两条观察，直接标记为 OBS-1/2 的重复件
 (7,  5, 3, 'CRACK', 'II',  10.000, 12.500, 'archive://2026/B3/frames/obs-007.zip', 'FULL',    'DUPLICATE', 1),
 (8,  5, 3, 'LEAK',  'III', 30.000, 31.000, 'archive://2026/B3/frames/obs-008.zip', 'PARTIAL', 'DUPLICATE', 2),
-- B3 重叠片段 V6 中的裂缝，聚类时并入同一簇，不重复累加
 (9,  6, 3, 'CRACK', 'II',  10.200, 12.300, 'archive://2026/B3/frames/obs-009.zip', 'FULL',    'PENDING', NULL),
-- WS-003（后拆分）：8–10 落入 3A；21–23 跨拆分点 22 ⇒ 隔离不得复制；30–31 落入 3B
 (10, 7, 1, 'CRACK',       'III',  8.000, 10.000, 'archive://2025/B1/frames/obs-010.zip', 'FULL',    'PENDING', NULL),
 (11, 7, 1, 'DEFORMATION', 'IV',  21.000, 23.000, 'archive://2025/B1/frames/obs-011.zip', 'FULL',    'PENDING', NULL),
 (12, 7, 1, 'LEAK',        'II',  30.000, 31.000, 'archive://2025/B1/frames/obs-012.zip', 'PARTIAL', 'PENDING', NULL),
-- B2 反向里程复检 WS-003：反向 30–32 ⇒ 归一 8–10，与 OBS-10 同位置同等级
 (13, 8, 2, 'CRACK',       'C',   30.000, 32.000, 'archive://2025/B2/frames/obs-013.zip', 'FULL',    'PENDING', NULL)
ON CONFLICT DO NOTHING;

-- 校正显式插入后的 identity 序列，避免后续自增冲突
SELECT setval(pg_get_serial_sequence('app_user','id'),               (SELECT max(id) FROM app_user));
SELECT setval(pg_get_serial_sequence('app_asset_version','id'),      (SELECT max(id) FROM app_asset_version));
SELECT setval(pg_get_serial_sequence('app_manhole','id'),            (SELECT max(id) FROM app_manhole));
SELECT setval(pg_get_serial_sequence('app_pipe_segment','id'),       (SELECT max(id) FROM app_pipe_segment));
SELECT setval(pg_get_serial_sequence('app_segment_mapping','id'),    (SELECT max(id) FROM app_segment_mapping));
SELECT setval(pg_get_serial_sequence('app_contractor_batch','id'),   (SELECT max(id) FROM app_contractor_batch));
SELECT setval(pg_get_serial_sequence('app_video','id'),              (SELECT max(id) FROM app_video));
SELECT setval(pg_get_serial_sequence('app_observation','id'),        (SELECT max(id) FROM app_observation));
