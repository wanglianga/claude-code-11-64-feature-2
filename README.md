# 排水管道结构缺陷普查评估平台

面向市政排水部门的可追溯普查评估平台：整合机器人管道检测、人工复核、竣工资产图与历次修复资料，
完成**位置对齐 → 去重聚类 → 冲突裁决 → 双口径风险顺位**的完整链路，为工程处安排复核与年度立项提供依据。

技术栈：**Vue 3 + Vite + Pinia + Vue Router（前端）｜Spring Boot 3.5 + JdbcTemplate（后端）｜PostgreSQL 18（数据库）｜Docker Compose（一键启动）**。

---

## 1. 核心业务规则（与题目逐条对应）

1. **按检查时点固定观察归属**：每条观察先按批次检查时间确定当时有效的资产版本，
   再把承包商里程换算为“上游井为 0”的归一里程并写入固定归属表；后续测绘修订只形成新旧管段里程映射与影响清单，
   历史归属行永不改写（对齐页可查看含落选参照的全部候选依据）。
2. **反向里程归一**：视频标记 `REVERSED`（0 在下游井）时按 `归一里程 = 管长 − 原始里程` 换算，区间端点交换。
3. **不相容等级冲突**：两批独立检查对同一位置给出不同等级时，仅当证据强度悬殊（FULL vs SPARSE 且 FULL 之间一致）
   才自动裁决；否则保留 `CONFLICT`，该管段在数据集中**暂停确定排序（无顺位、置底展示）**，等待人工复核裁决。
4. **管段拆分继承**：只有归一区间被里程映射证实**完整落入**新管段的缺陷才继承；
   跨拆分点的记录隔离并在影响清单登记，**不会复制到两段**。
5. **重复不累加**：批次内容哈希重复直接拒绝（HTTP 409）；视频内容哈希重复登记为重复视频，其观察不参与对齐；
   重叠片段中的同位置观察在聚类时并为同一缺陷簇，同等级多批次仅计一次。
6. **两种风险口径，公式对使用者全程可见**：
   - 最高严重等级（MAX_SEVERITY）：取管段内经确认缺陷的最高归一等级，同分按负担分次序——突出局部失效；
   - 累计负担分（BURDEN）：`Σ 缺陷区间长度(m) × 等级(1-4) × 证据权重(FULL=1.0/PARTIAL=0.8/SPARSE=0.6)`——体现广泛劣化。
7. **判读规则门槛**：缺陷需同时满足最低等级、最短区间长度、最低证据强度才进入数据集；规则可在页面调整后重新生成。

## 2. 目录结构

```
64/
├── docker-compose.yml          # 一键启动 postgres + backend + frontend
├── backend/                    # Spring Boot 工程
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/ccdev/drainage/   # config/auth/domain/repo/service/web/dto
│       └── resources/
│           ├── application.yml
│           └── db/
│               ├── schema.sql        # 17 张表，IF NOT EXISTS 可重复执行
│               └── seed-data.sql     # 虚构样例（反向里程/拆分/冲突/重复片段）
└── frontend/                   # Vue 3 工程
    ├── Dockerfile  nginx.conf
    └── src/                    # views 7 个页面 + stores/router/api/components
```

## 3. 一键启动（Docker Compose）

```bash
cd /workspace/64
docker compose up --build
```

启动后：

| 服务 | 地址 | 说明 |
| --- | --- | --- |
| 前端（nginx） | http://localhost:8090 | 浏览器入口，/api 反代后端 |
| 后端 API | http://localhost:8080 | 启动时自动建表、灌入样例并跑通 对齐→聚类→双口径数据集 |
| PostgreSQL | localhost:5432 | 库/用户/密码均为 `ccdev` |

停止并清空数据卷：`docker compose down -v`

> 说明：本交付环境内未安装 Docker 守护进程，Compose 配置已按规范提供，构建与运行验证使用本地原生运行时完成（见第 6 节）。

## 4. 本地开发启动（不带 Docker）

前置：PostgreSQL 已运行且存在 `ccdev` 库（本环境为 127.0.0.1:5432，账号密码 `ccdev`）。

```bash
# 终端 1：后端（默认读取 classpath 下 schema.sql/seed-data.sql，自动初始化）
cd backend
mvn spring-boot:run

# 终端 2：前端（Vite 5173，/api 代理到 8080）
cd frontend
npm install
npm run dev
```

浏览器访问 http://localhost:5173 。数据库连接可用环境变量覆盖：
`DB_HOST / DB_PORT / DB_NAME / DB_USER / DB_PASSWORD`。

## 5. 测试账号

| 角色 | 用户名 | 密码 | 用途 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部操作，含规则调整与冲突裁决 |
| 复核工程师 | `engineer` | `eng123` | 复核、裁决、生成数据集 |

认证方式：`POST /api/auth/login` 返回令牌，后续请求携带请求头 `X-Auth-Token`。

## 6. 验证方式

### 6.1 自动化测试（已实际执行，12 个用例全部通过）

```bash
# 需要可连接的 PostgreSQL；测试自动使用独立库 ccdev_test（静态块重建 schema）
createdb ccdev_test   # 或：psql -c 'CREATE DATABASE ccdev_test;'
cd backend
mvn test
```

覆盖：反向里程归一、时点版本固定与候选落选、重复视频/重叠片段不重复累加、不相容等级冲突与暂停排序、
拆分继承与跨点隔离不复制、双口径评分与解释、历史版本复现、重复上传 409 拦截、人工裁决恢复排序。

### 6.2 页面手工验收脚本（虚构样例数据）

| # | 验收点 | 操作路径与预期 |
| --- | --- | --- |
| 1 | 反向里程归属 | 检测归档页查看 OBS-3（反向[47.5,50]）→ 固定归一区间 [10,12.5]，与 OBS-1 同位置；归属固定于 V2024-01 |
| 2 | 候选依据 | 点击 OBS-3“对齐依据”：V2026-06 同编码管段为落选参照，原因写明“检查时点固定归属” |
| 3 | 冲突暂停 | 缺陷聚类页 WS-002 接口错位簇显示“冲突暂停”（III/FULL vs D=4/PARTIAL）；数据集页 WS-002 无顺位、置底 |
| 4 | 重复不累加 | 视频页 VID-B3-WS001-DUP 标红“重复视频”；WS-001 裂缝簇 3 成员仅 1 个主张，风险不翻倍 |
| 5 | 拆分继承 | 数据集影响清单：WS-003→WS-003A 继承 1、隔离 1；→WS-003B 继承 1、隔离 1；OBS-11 变形[21,23]跨 22m 拆分点被隔离 |
| 6 | 双口径 | 数据集页切换 MAX_SEVERITY / BURDEN 重新生成，公式与每段顺位解释同步展示（样例 WS-001 负担分 9.800） |
| 7 | 历史复现 | 选择 V2024-01 生成数据集：WS-003 仍为整段、3 个入围缺陷且不暂停，证明修订未搬动历史缺陷 |
| 8 | 上传拦截 | 归档上传页提交内容哈希 `hash:batch:b202501` 的新批次 → 409 拒绝；哈希 `sha256:vid-ws001-full` 的视频 → 标记重复并给重叠警示 |
| 9 | 裁决恢复 | 聚类页对冲突簇裁决等级 4 → 重新生成数据集后 WS-002 获得顺位，动作写入审计日志 |

### 6.3 关键接口速查（均需 `X-Auth-Token`，登录接口除外）

```
POST /api/auth/login                 登录
GET  /api/assets/versions            资产版本谱系
GET  /api/inspections/observations/{id}/candidates   对齐候选依据
GET  /api/clusters                   缺陷聚类与冲突
POST /api/clusters/{id}/adjudicate   人工裁决
POST /api/datasets/generate          按版本+口径生成数据集
GET  /api/datasets/{id}/scores       管段顺位与解释
GET  /api/datasets/{id}/impacts      图纸修订影响清单
```

## 7. 虚构样例数据说明

- 资产：2 个版本。V2024-01 含 WS-001/WS-002/WS-003；V2026-06 将 WS-003 在 22m 处拆分为 WS-003A/WS-003B（新增井 MH03X）。
- 检测：3 个批次（甲勘测院 2025-03、乙检测公司 2025-09 反向里程、甲复测 2026-03），8 个视频、14 条观察，
  覆盖裂缝/变形/渗漏/接口错位四类缺陷与 I–IV、A–D 两套分级标准。
- 所有数据均为虚构，视频 URI 使用 `archive://` 占位索引，不依赖真实媒体文件。
