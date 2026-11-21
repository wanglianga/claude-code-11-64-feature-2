<template>
  <div>
    <div class="notice info">
      本工作台比较各承包商在<b>共享样本</b>上的判读差异：差异只在同一样本内部比较（留一法同侪共识），
      避免把承包商负责管段的难度差异误当成评分偏差。质量画像只发布结果，<b>不会自动改写已发布的缺陷等级</b>。
    </div>

    <div class="tabs">
      <button :class="tab === 'samples' ? 'active' : ''" @click="tab = 'samples'">共同样本库</button>
      <button :class="tab === 'analysis' ? 'active' : ''" @click="tab = 'analysis'">分层一致性</button>
      <button :class="tab === 'profiles' ? 'active' : ''" @click="tab = 'profiles'">质量画像版本</button>
    </div>

    <!-- ------------------------------------------------------------ 样本库 -->
    <div v-if="tab === 'samples'">
      <div class="panel">
        <h2>样本抽取</h2>
        <div class="muted" style="margin-bottom: 12px">
          方案 A（设计分层）：按严重度分层抽取共同视频样本让各方盲评，便于形成可比较配对差异，但需额外复核工时、样本更新较慢；
          方案 B（自然重叠）：利用日常任务中自然重叠的复检记录建模，覆盖更多真实案件，但受各家负责区域难度与复检选择偏差影响。
          两种方案都只估计判读一致性，请按共有样本规模与复核成本选择，并注意下方覆盖边界。
        </div>
        <div class="form-grid">
          <div>
            <label>规程版本</label>
            <select v-model="drawForm.standardCode">
              <option>STD-CCT-2021</option>
              <option>STD-DX-2023</option>
            </select>
          </div>
          <div><label>MINOR 配额</label><input v-model.number="drawForm.minor" type="number" min="0" /></div>
          <div><label>MODERATE 配额</label><input v-model.number="drawForm.moderate" type="number" min="0" /></div>
          <div><label>SEVERE 配额</label><input v-model.number="drawForm.severe" type="number" min="0" /></div>
          <div>
            <label>判读方（多选）</label>
            <select v-model="drawForm.readerIds" multiple size="4">
              <option v-for="r in readers" :key="r.id" :value="r.id">{{ r.readerCode }} · {{ r.readerName }}</option>
            </select>
          </div>
          <div><label>备注</label><input v-model="drawForm.note" placeholder="本轮盲评安排" /></div>
        </div>
        <div class="flex" style="margin-top: 14px">
          <button class="btn" @click="drawDesigned">分层抽取（设计样本）</button>
          <button class="btn secondary" @click="detectNatural">识别自然重叠复检</button>
        </div>
        <div v-if="drawMessage" class="notice" :class="drawMessageType" style="margin-top: 12px">{{ drawMessage }}</div>
        <div v-if="drawDetail" class="explain">{{ drawDetail }}</div>
      </div>

      <div class="panel">
        <h2>共同样本库（同一片段只算一个样本，多人评分只增加判读）</h2>
        <div class="flex" style="margin-bottom: 10px">
          <label style="margin: 0">方法筛选</label>
          <select v-model="sampleFilter.method" style="width: 160px" @change="loadSamples">
            <option value="ALL">全部</option>
            <option value="DESIGNED">设计分层</option>
            <option value="NATURAL">自然重叠</option>
          </select>
          <label style="margin: 0">规程</label>
          <select v-model="sampleFilter.standardCode" style="width: 170px" @change="loadSamples">
            <option value="">全部</option>
            <option>STD-CCT-2021</option>
            <option>STD-DX-2023</option>
          </select>
        </div>
        <table>
          <thead>
            <tr><th>样本</th><th>来源</th><th>规程</th><th>缺陷类型</th><th>严重度层</th><th>区域/难度</th><th>有效判读</th><th>判读方</th><th></th></tr>
          </thead>
          <tbody>
            <tr v-for="row in samples" :key="row.sample.id">
              <td><b>{{ row.sample.sampleCode }}</b><div class="muted">{{ row.sample.segmentCode }} [{{ row.sample.clipStartM }}, {{ row.sample.clipEndM }}]</div></td>
              <td><span class="badge" :class="row.sample.sourceType === 'DESIGNED_STRATIFIED' ? 'blue' : 'teal'">{{ row.sample.sourceType === 'DESIGNED_STRATIFIED' ? '设计分层' : '自然重叠' }}</span></td>
              <td><span class="badge gray">{{ row.sample.standardCode }}</span></td>
              <td>{{ defectText(row.sample.defectType) }}</td>
              <td><span class="badge" :class="bandClass(row.sample.severityBand)">{{ bandText(row.sample.severityBand) }}</span></td>
              <td>{{ row.sample.regionCode }} / {{ difficultyText(row.sample.difficultyBand) }}</td>
              <td>{{ row.activeReadingCount }} / {{ row.totalReadingCount }}</td>
              <td class="muted">{{ row.readers.join('、') || '—' }}</td>
              <td><button class="btn secondary small" @click="showEvidence(row.sample.id)">证据与配对</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h2>判读录入（同一判读方对同一样本只能有一条有效判读，更正走版本链）</h2>
        <div class="form-grid">
          <div><label>样本 ID</label><input v-model.number="readingForm.sampleId" type="number" /></div>
          <div>
            <label>判读方</label>
            <select v-model.number="readingForm.readerId">
              <option v-for="r in readers" :key="r.id" :value="r.id">{{ r.readerCode }} · {{ r.readerName }}</option>
            </select>
          </div>
          <div><label>原始等级</label><input v-model="readingForm.rawGrade" placeholder="II / B" /></div>
          <div><label>来源批次（可选，决定规程）</label><input v-model.number="readingForm.sourceBatchId" type="number" placeholder="留空用样本规程" /></div>
          <div>
            <label>盲评？</label>
            <select v-model="readingForm.blind"><option :value="true">是（未看首评）</option><option :value="false">否（已看首评）</option></select>
          </div>
          <div><label>首评记录 ID（看到首评后打分则填写）</label><input v-model.number="readingForm.seenPriorReadingId" type="number" placeholder="留空=独立盲评" /></div>
          <div><label>备注</label><input v-model="readingForm.note" /></div>
        </div>
        <div class="flex" style="margin-top: 14px">
          <button class="btn" @click="submitReading">提交判读</button>
        </div>
        <div v-if="readingMessage" class="notice" :class="readingMessageType" style="margin-top: 12px">{{ readingMessage }}</div>
      </div>
    </div>

    <!-- ------------------------------------------------------------ 分层一致性 -->
    <div v-if="tab === 'analysis'">
      <div class="panel">
        <h2>分层一致性结果</h2>
        <div class="flex" style="margin-bottom: 12px">
          <label style="margin: 0">方法</label>
          <select v-model="analysisQuery.method" style="width: 160px" @change="loadAnalysis">
            <option value="ALL">全部</option>
            <option value="DESIGNED">设计分层</option>
            <option value="NATURAL">自然重叠</option>
          </select>
          <label style="margin: 0">规程</label>
          <select v-model="analysisQuery.standardCode" style="width: 170px" @change="loadAnalysis">
            <option value="">全部</option>
            <option>STD-CCT-2021</option>
            <option>STD-DX-2023</option>
          </select>
        </div>
        <div v-if="analysis" class="stat-grid">
          <div class="stat"><div class="num">{{ analysis.summary.sampleCount }}</div><div class="lbl">样本数</div></div>
          <div class="stat"><div class="num">{{ analysis.summary.independentSampleCount }}</div><div class="lbl">具独立盲评配对样本</div></div>
          <div class="stat"><div class="num">{{ analysis.summary.pairedObservationCount }}</div><div class="lbl">配对观察</div></div>
          <div class="stat"><div class="num">{{ analysis.summary.excludedNonBlind }}</div><div class="lbl">排除：非独立盲评</div></div>
          <div class="stat"><div class="num">{{ analysis.summary.excludedCrossStandard }}</div><div class="lbl">排除：跨规程</div></div>
          <div class="stat"><div class="num">{{ analysis.summary.mergedDuplicateSamples }}</div><div class="lbl">合并重复样本</div></div>
        </div>
        <div v-for="(w, i) in (analysis?.warnings || [])" :key="i" class="notice warn">{{ w }}</div>
      </div>

      <div v-for="s in (analysis?.strata || [])" :key="s.standardCode + s.defectType + s.severityBand + s.regionCode" class="panel">
        <h2>
          {{ s.standardCode }} · {{ defectText(s.defectType) }} · {{ bandText(s.severityBand) }}
          <span class="badge" :class="s.regionCode === 'ALL' ? 'gray' : 'teal'">{{ s.regionCode === 'ALL' ? '全局' : '区域 ' + s.regionCode }}</span>
          <span class="muted" style="font-weight: 400">样本 {{ s.distinctSampleN }} · 配对 {{ s.pairedN }}</span>
        </h2>
        <div v-if="s.distinctSampleN < 3" class="notice warn">
          该分层有效样本低于 3 个，偏差与区间仅供定性参考，不得据此对承包商下结论。
        </div>
        <table>
          <thead>
            <tr><th>判读方</th><th>配对</th><th>样本</th><th>平均偏差</th><th>偏差 95% 区间</th><th>分歧指数</th><th>一致率</th><th>一致率 95% 区间</th><th>±1 内</th><th>偏严/偏松</th></tr>
          </thead>
          <tbody>
            <tr v-for="r in s.readers" :key="r.readerId">
              <td><b>{{ readerCode(r.readerId) }}</b> <span v-if="r.sparse" class="badge amber">样本稀少</span></td>
              <td>{{ r.pairedN }}</td>
              <td>{{ r.distinctSampleN }}</td>
              <td class="mono">{{ fmt(r.meanBias) }}</td>
              <td class="mono">[{{ fmt(r.biasCi[0]) }}, {{ fmt(r.biasCi[1]) }}]</td>
              <td class="mono">{{ fmt(r.disagreementIndex) }}</td>
              <td class="mono">{{ pct(r.agreementRate) }}</td>
              <td class="mono">[{{ pct(r.agreementCi[0]) }}, {{ pct(r.agreementCi[1]) }}]</td>
              <td class="mono">{{ pct(r.withinOneRate) }}</td>
              <td>{{ r.stricterCount }} / {{ r.looserCount }}</td>
            </tr>
          </tbody>
        </table>
        <h3>混淆分布（行=承包商判读等级，列=同侪共识等级）</h3>
        <table>
          <thead><tr><th>判读 \ 共识</th><th>1</th><th>2</th><th>3</th><th>4</th></tr></thead>
          <tbody>
            <tr v-for="c in s.confusion" :key="c.observedGrade">
              <td><b>{{ c.observedGrade }}</b></td>
              <td>{{ c.consensus1 }}</td><td>{{ c.consensus2 }}</td><td>{{ c.consensus3 }}</td><td>{{ c.consensus4 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ------------------------------------------------------------ 质量画像 -->
    <div v-if="tab === 'profiles'">
      <div class="panel">
        <h2>发布质量画像（新版本，旧画像保留可查）</h2>
        <div class="muted" style="margin-bottom: 10px">
          画像只读取判读记录，发布不会改写已发布的缺陷等级。发生判读更正后应重新发布，新版本会列出受影响批次。
        </div>
        <div class="form-grid">
          <div>
            <label>方法</label>
            <select v-model="publishForm.method">
              <option value="DESIGNED">设计分层</option>
              <option value="NATURAL">自然重叠</option>
              <option value="ALL">全部</option>
            </select>
          </div>
          <div>
            <label>规程范围</label>
            <select v-model="publishForm.standardScope">
              <option value="ALL">全部</option>
              <option>STD-CCT-2021</option>
              <option>STD-DX-2023</option>
            </select>
          </div>
          <div><label>修订原因（重新发布时填写）</label><input v-model="publishForm.revisionReason" /></div>
        </div>
        <div class="flex" style="margin-top: 14px">
          <button class="btn" @click="publish">发布新版本</button>
        </div>
        <div v-if="publishMessage" class="notice" :class="publishMessageType" style="margin-top: 12px">{{ publishMessage }}</div>
      </div>

      <div class="panel">
        <h2>画像版本</h2>
        <table>
          <thead><tr><th>画像</th><th>方法/规程</th><th>版本</th><th>状态</th><th>样本/配对</th><th>覆盖边界</th><th>受影响批次</th><th></th></tr></thead>
          <tbody>
            <tr v-for="p in profiles" :key="p.id">
              <td><b>{{ p.profileCode }}</b><div class="muted">{{ p.generatedAt }}</div></td>
              <td>{{ p.method }} / {{ p.standardScope }}</td>
              <td>V{{ p.versionNo }}</td>
              <td><span class="badge" :class="p.status === 'PUBLISHED' ? 'green' : 'gray'">{{ p.status === 'PUBLISHED' ? '现行' : '已被取代' }}</span></td>
              <td>{{ p.sampleCount }} / {{ p.pairedObservationCount }}</td>
              <td style="max-width: 380px"><div class="muted">{{ p.severityCoverageNote }}</div></td>
              <td class="muted">{{ p.affectedBatches || '—' }}</td>
              <td><button class="btn secondary small" @click="viewProfile(p.id)">查看指标</button></td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="profileDetail" class="panel">
        <h2>{{ profileDetail.profile.profileCode }} · 指标（不回写缺陷等级）</h2>
        <div class="muted" style="margin-bottom: 8px">{{ profileDetail.profile.coverageNote }}</div>
        <table>
          <thead><tr><th>判读方</th><th>规程</th><th>缺陷</th><th>层</th><th>区域</th><th>配对</th><th>平均偏差</th><th>偏差区间</th><th>一致率</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="m in profileDetail.metrics" :key="m.id">
              <td>{{ readerCode(m.readerId) }}</td>
              <td><span class="badge gray">{{ m.standardCode }}</span></td>
              <td>{{ defectText(m.defectType) }}</td>
              <td>{{ bandText(m.severityBand) }}</td>
              <td>{{ m.regionCode }}</td>
              <td>{{ m.pairedN }}</td>
              <td class="mono">{{ fmt(m.meanBias) }}</td>
              <td class="mono">[{{ fmt(m.biasCiLow) }}, {{ fmt(m.biasCiHigh) }}]</td>
              <td class="mono">{{ pct(m.agreementRate) }}</td>
              <td><span class="badge" :class="m.metricStatus === 'SPARSE' ? 'amber' : 'green'">{{ m.metricStatus === 'SPARSE' ? '样本稀少' : '正常' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- ------------------------------------------------------------ 证据弹窗 -->
    <div v-if="evidence" class="modal-mask" @click.self="evidence = null">
      <div class="modal" style="width: 760px">
        <h3>样本 {{ evidence.sample.sampleCode }} · 证据与配对</h3>
        <div class="muted" style="margin-bottom: 10px">
          规程 {{ evidence.sample.standardCode }} · 分层严重度 {{ bandText(evidence.effectiveSeverityBand) }} ·
          同侪共识均值 {{ evidence.consensusMean }}（等级 {{ evidence.consensusGrade }}）· 有效判读 {{ evidence.eligibleReadingCount }}
        </div>
        <table>
          <thead><tr><th>判读方</th><th>盲评身份</th><th>等级</th><th>规程</th><th>同侪均值</th><th>配对差异</th><th>方向</th><th>版本</th></tr></thead>
          <tbody>
            <tr v-for="p in evidence.pairs" :key="p.readingId">
              <td>{{ p.readerCode }}</td>
              <td class="mono">{{ p.blindIdentity }}</td>
              <td>{{ p.rawGrade }} ({{ p.normalizedGrade }})</td>
              <td>{{ p.standardCode }}</td>
              <td class="mono">{{ p.peerMean }}</td>
              <td class="mono">{{ p.pairedDifference }}</td>
              <td><span class="badge" :class="p.direction === 'STRICTER' ? 'amber' : p.direction === 'LOOSER' ? 'blue' : 'green'">{{ directionText(p.direction) }}</span></td>
              <td>{{ p.revisionNo }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="evidence.excludedReadings.length" class="notice warn" style="margin-top: 12px">
          以下记录已排除：
          <div v-for="(e, i) in evidence.excludedReadings" :key="i">
            #{{ e.reading.id }} {{ e.readerCode?.readerCode || ('R#' + e.reading.readerId) }} {{ e.reading.rawGrade }} — {{ e.reason }}
          </div>
        </div>
        <h3>更正历史</h3>
        <table>
          <thead><tr><th>记录</th><th>等级</th><th>版本</th><th>有效</th><th>被取代为</th><th>原因</th></tr></thead>
          <tbody>
            <tr v-for="r in evidence.allReadings" :key="r.id">
              <td>#{{ r.id }}</td>
              <td>{{ r.rawGrade }}</td>
              <td>{{ r.revisionNo }}</td>
              <td><span class="badge" :class="r.active ? 'green' : 'gray'">{{ r.active ? '现行' : '历史' }}</span></td>
              <td>{{ r.supersededById || '—' }}</td>
              <td class="muted">{{ r.correctionReason || r.note || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div class="flex" style="margin-top: 12px">
          <div style="flex: 1"><input v-model="correctForm.rawGrade" placeholder="更正等级，如 II / B" /></div>
          <div style="flex: 2"><input v-model="correctForm.reason" placeholder="更正原因（必填）" /></div>
          <button class="btn secondary" @click="submitCorrection">更正所选记录</button>
        </div>
        <div class="form-grid" style="margin-top: 8px">
          <div><label>要更正的记录 ID</label><input v-model.number="correctForm.readingId" type="number" /></div>
        </div>
        <div v-if="correctMessage" class="notice info" style="margin-top: 10px">{{ correctMessage }}</div>
        <button class="btn secondary" style="margin-top: 12px" @click="evidence = null">关闭</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import http from '../api/http'

const tab = ref('samples')
const readers = ref([])
const samples = ref([])
const analysis = ref(null)
const profiles = ref([])
const profileDetail = ref(null)
const evidence = ref(null)
const correctMessage = ref('')

const sampleFilter = reactive({ method: 'ALL', standardCode: '' })
const analysisQuery = reactive({ method: 'ALL', standardCode: '' })
const drawForm = reactive({ standardCode: 'STD-DX-2023', minor: 1, moderate: 1, severe: 1, readerIds: [2, 4], note: '' })
const readingForm = reactive({ sampleId: null, readerId: 1, rawGrade: '', sourceBatchId: null, blind: true, seenPriorReadingId: null, note: '' })
const publishForm = reactive({ method: 'DESIGNED', standardScope: 'ALL', revisionReason: '' })
const correctForm = reactive({ readingId: null, rawGrade: '', reason: '' })

const drawMessage = ref('')
const drawMessageType = ref('info')
const drawDetail = ref('')
const readingMessage = ref('')
const readingMessageType = ref('info')
const publishMessage = ref('')
const publishMessageType = ref('info')

const defectText = (t) => ({ CRACK: '裂缝', DEFORMATION: '变形', LEAK: '渗漏', JOINT_DISLOCATION: '接口错位' }[t] || t)
const bandText = (b) => ({ MINOR: '轻微', MODERATE: '中等', SEVERE: '严重' }[b] || b)
const bandClass = (b) => ({ MINOR: 'blue', MODERATE: 'amber', SEVERE: 'red' }[b] || 'gray')
const difficultyText = (d) => ({ LOW: '低', MID: '中', HIGH: '高' }[d] || d)
const directionText = (d) => ({ STRICTER: '偏严', LOOSER: '偏松', AGREE: '一致' }[d] || d)
const fmt = (v) => (v === null || v === undefined ? '—' : Number(v).toFixed(2))
const pct = (v) => (v === null || v === undefined ? '—' : (Number(v) * 100).toFixed(0) + '%')
const readerCode = (id) => readers.value.find((r) => r.id === id)?.readerCode || 'R#' + id

onMounted(async () => {
  await loadReaders()
  await Promise.all([loadSamples(), loadAnalysis(), loadProfiles()])
})

async function loadReaders() {
  const { data } = await http.get('/consistency/readers')
  readers.value = data
}

async function loadSamples() {
  const params = {}
  if (sampleFilter.method && sampleFilter.method !== 'ALL') params.method = sampleFilter.method
  if (sampleFilter.standardCode) params.standardCode = sampleFilter.standardCode
  const { data } = await http.get('/consistency/samples', { params })
  samples.value = data
}

async function loadAnalysis() {
  const params = { method: analysisQuery.method }
  if (analysisQuery.standardCode) params.standardCode = analysisQuery.standardCode
  const { data } = await http.get('/consistency/analysis', { params })
  analysis.value = data
}

async function loadProfiles() {
  const { data } = await http.get('/consistency/profiles')
  profiles.value = data
}

async function drawDesigned() {
  drawMessage.value = ''
  drawDetail.value = ''
  try {
    const { data } = await http.post('/consistency/samples/draw-designed', {
      standardCode: drawForm.standardCode,
      perBandQuota: { MINOR: drawForm.minor, MODERATE: drawForm.moderate, SEVERE: drawForm.severe },
      readerIds: drawForm.readerIds,
      note: drawForm.note
    })
    drawMessage.value = `已抽取 ${data.createdSamples} 个设计样本：${data.sampleCodes.join('、') || '无新增'}`
    drawMessageType.value = data.createdSamples > 0 ? 'ok' : 'warn'
    drawDetail.value = data.costNote
    await loadSamples()
  } catch (e) {
    drawMessage.value = e.response?.data?.error || '抽取失败'
    drawMessageType.value = 'error'
  }
}

async function detectNatural() {
  drawMessage.value = ''
  try {
    const { data } = await http.post('/consistency/samples/detect-natural')
    drawMessage.value = `识别到 ${data.createdSamples} 个自然重叠样本：${data.sampleCodes.join('、') || '无新增'}`
    drawMessageType.value = data.createdSamples > 0 ? 'ok' : 'warn'
    drawDetail.value = data.biasWarning
    await loadSamples()
  } catch (e) {
    drawMessage.value = e.response?.data?.error || '识别失败'
    drawMessageType.value = 'error'
  }
}

async function submitReading() {
  readingMessage.value = ''
  try {
    const payload = { ...readingForm }
    if (!payload.sourceBatchId) delete payload.sourceBatchId
    if (!payload.seenPriorReadingId) delete payload.seenPriorReadingId
    const { data } = await http.post('/consistency/readings', payload)
    readingMessage.value = `判读已登记 #${data.readingId}，等级 ${data.normalizedGrade}，规程 ${data.standardCode}；`
      + (data.eligibleAsEvidence ? '可作为独立盲评证据。' : `不计入一致性：${data.exclusionReason}`)
    readingMessageType.value = data.eligibleAsEvidence ? 'ok' : 'warn'
    await loadSamples()
  } catch (e) {
    readingMessage.value = e.response?.data?.error || '提交失败'
    readingMessageType.value = 'error'
  }
}

async function showEvidence(sampleId) {
  correctMessage.value = ''
  const { data } = await http.get(`/consistency/samples/${sampleId}/evidence`)
  evidence.value = data
}

async function submitCorrection() {
  correctMessage.value = ''
  try {
    const { data } = await http.post(`/consistency/readings/${correctForm.readingId}/correct`, {
      rawGrade: correctForm.rawGrade, reason: correctForm.reason
    })
    correctMessage.value = `已生成版本 #${data.newReadingId}（第 ${data.revisionNo} 版），旧记录 #${data.supersededReadingId} 保留可查；受影响批次：${data.affectedBatches || '无'}。`
    const sid = evidence.value.sample.id
    await showEvidence(sid)
    correctMessage.value = `已生成版本 #${data.newReadingId}（第 ${data.revisionNo} 版），旧记录 #${data.supersededReadingId} 保留可查；受影响批次：${data.affectedBatches || '无'}。`
  } catch (e) {
    correctMessage.value = e.response?.data?.error || '更正失败'
  }
}

async function publish() {
  publishMessage.value = ''
  try {
    const { data } = await http.post('/consistency/profiles/publish', publishForm)
    publishMessage.value = `已发布 ${data.profileCode}（V${data.versionNo}），沿用 ${data.sampleCount} 个样本、${data.pairedObservationCount} 条配对；`
      + `${data.severityCoverageNote}；受影响批次：${data.affectedBatches || '无'}。`
    publishMessageType.value = 'ok'
    await loadProfiles()
    await loadAnalysis()
  } catch (e) {
    publishMessage.value = e.response?.data?.error || '发布失败'
    publishMessageType.value = 'error'
  }
}

async function viewProfile(id) {
  const { data } = await http.get(`/consistency/profiles/${id}`)
  profileDetail.value = data
}
</script>
