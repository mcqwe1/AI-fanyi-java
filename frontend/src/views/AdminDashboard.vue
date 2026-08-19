<template>
  <div class="admin-page">
    <div class="page-head admin-head">
      <div>
        <div class="eyebrow"><i class="el-icon-s-tools" /> 管理中心</div>
        <h2>管理员后台</h2>
        <p>统一查看系统运行状态、用户和翻译任务。管理操作会实时作用于当前软件。</p>
      </div>
      <el-button icon="el-icon-refresh" :loading="loading" @click="loadAll">刷新数据</el-button>
    </div>

    <el-alert v-if="error" type="warning" :closable="false" show-icon class="admin-alert"
              title="后台数据暂时无法加载，请确认后端服务正在运行。" />

    <div class="stat-grid">
      <el-card v-for="card in statCards" :key="card.label" class="stat-card" shadow="never">
        <div class="stat-icon" :class="card.tone"><i :class="card.icon" /></div>
        <div class="stat-copy"><span>{{ card.label }}</span><strong>{{ card.value }}</strong><small>{{ card.hint }}</small></div>
      </el-card>
    </div>

    <el-card class="admin-card" shadow="never">
      <el-tabs v-model="activeTab">
        <el-tab-pane name="overview">
          <span slot="label"><i class="el-icon-data-analysis" /> 运行概览</span>
          <div class="overview-grid">
            <div class="overview-panel">
              <div class="panel-title">任务状态分布</div>
              <div class="status-list">
                <div v-for="item in taskStatusCards" :key="item.label" class="status-row">
                  <span><i :class="item.icon" :style="{ color: item.color }" /> {{ item.label }}</span>
                  <b>{{ item.value }}</b>
                </div>
              </div>
            </div>
            <div class="overview-panel health-panel">
              <div class="panel-title">系统健康度</div>
              <div class="health-score"><span>运行正常</span><strong>稳定</strong></div>
              <el-progress :percentage="healthPercentage" :show-text="false" :stroke-width="10" color="#4c7dff" />
              <p>当前有 {{ summary.processingTasks || 0 }} 个任务正在处理，今日新建 {{ summary.todayTasks || 0 }} 个任务。</p>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane name="users">
          <span slot="label"><i class="el-icon-user" /> 用户管理</span>
          <div class="toolbar">
            <el-input v-model="userKeyword" clearable prefix-icon="el-icon-search" placeholder="搜索用户名或昵称" @keyup.enter.native="loadUsers" />
            <el-button type="primary" icon="el-icon-search" @click="loadUsers">搜索</el-button>
          </div>
          <el-table v-loading="usersLoading" :data="users" stripe empty-text="暂无用户">
            <el-table-column label="用户" min-width="210">
              <template slot-scope="{ row }">
                <div class="user-cell"><span class="avatar">{{ avatarText(row) }}</span><div><b>{{ row.nickname || row.username }}</b><small>@{{ row.username }}</small></div></div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="145">
              <template slot-scope="{ row }">
                <el-select v-model="row.role" size="mini" @change="updateUser(row, { role: row.role })">
                  <el-option label="管理员" value="ADMIN" /><el-option label="普通用户" value="USER" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template slot-scope="{ row }">
                <el-switch v-model="row.enabled" :active-value="1" :inactive-value="0" @change="updateUser(row, { enabled: row.enabled })" />
              </template>
            </el-table-column>
            <el-table-column prop="taskCount" label="任务数" width="90" />
            <el-table-column label="注册时间" width="175"><template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="状态说明" min-width="140"><template slot-scope="{ row }"><el-tag size="mini" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可正常登录' : '已停用' }}</el-tag></template></el-table-column>
            <el-table-column label="翻译记录" width="110"><template slot-scope="{ row }"><el-button type="text" icon="el-icon-view" @click="viewUserTasks(row)">查看记录</el-button></template></el-table-column>
          </el-table>
          <div class="table-note">共展示最近 {{ users.length }} 个用户，最多加载 200 条。停用账户后，该账户的登录和接口访问会立即失效。</div>
        </el-tab-pane>

        <el-tab-pane name="concurrency">
          <span slot="label"><i class="el-icon-connection" /> 接口并发</span>
          <div class="toolbar">
            <el-input v-model="apiKeyword" clearable prefix-icon="el-icon-search" placeholder="搜索接口路径" />
            <el-button icon="el-icon-refresh" :loading="concurrencyLoading" @click="loadConcurrency">刷新</el-button>
          </div>
          <el-table v-loading="concurrencyLoading" :data="filteredConcurrency" stripe empty-text="暂无接口调用数据">
            <el-table-column prop="method" label="方法" width="90"><template slot-scope="{ row }"><el-tag size="mini">{{ row.method }}</el-tag></template></el-table-column>
            <el-table-column prop="path" label="接口路径" min-width="330" />
            <el-table-column prop="active" label="当前并发数" width="130"><template slot-scope="{ row }"><b :class="{ 'active-concurrency': row.active > 0 }">{{ row.active }}</b></template></el-table-column>
            <el-table-column prop="waiting" label="等待数" width="95" />
            <el-table-column prop="limit" label="并发上限" width="105" />
            <el-table-column prop="peak" label="峰值并发数" width="130" />
            <el-table-column prop="total" label="累计调用次数" width="140" />
            <el-table-column prop="rejected" label="拒绝次数" width="110"><template slot-scope="{ row }"><span :class="{ 'record-error': row.rejected > 0 }">{{ row.rejected }}</span></template></el-table-column>
          </el-table>
          <div class="table-note">当前并发数为正在处理中的请求数；峰值并发和累计次数从本次软件启动后开始统计。</div>
        </el-tab-pane>
        <el-tab-pane name="tasks">
          <span slot="label"><i class="el-icon-tickets" /> 任务监控</span>
          <div class="toolbar">
            <el-select v-model="taskStatus" placeholder="全部状态" clearable @change="loadTasks"><el-option label="全部状态" value="ALL" /><el-option label="处理中" value="PROCESSING" /><el-option label="已完成" value="DONE" /><el-option label="失败" value="FAILED" /></el-select>
            <el-input v-model="taskKeyword" clearable prefix-icon="el-icon-search" placeholder="按文件名搜索" @keyup.enter.native="loadTasks" />
            <el-button type="primary" icon="el-icon-search" @click="loadTasks">筛选</el-button>
          </div>
          <el-table v-loading="tasksLoading" :data="tasks" stripe empty-text="暂无任务">
            <el-table-column prop="id" label="ID" width="75" />
            <el-table-column label="任务文件" min-width="230"><template slot-scope="{ row }"><div class="file-cell"><i :class="row.mediaType === 'AUDIO' ? 'el-icon-headset' : 'el-icon-video-camera'" /><div><b>{{ row.originalFilename || '未命名文件' }}</b><small>{{ row.username }} · {{ row.mode || 'NORMAL' }}</small></div></div></template></el-table-column>
            <el-table-column label="语言" width="130"><template slot-scope="{ row }">{{ row.sourceLang || 'auto' }} → {{ row.targetLang || '-' }}</template></el-table-column>
            <el-table-column label="状态" width="180"><template slot-scope="{ row }"><el-tag size="mini" :type="taskTagType(row.status)">{{ taskStatusText(row.status) }}</el-tag><el-progress v-if="row.status !== 'DONE' && row.status !== 'FAILED'" :percentage="row.progress || 0" :stroke-width="5" /></template></el-table-column>
            <el-table-column label="创建时间" width="175"><template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template slot-scope="{ row }"><el-button type="text" class="danger-text" @click="removeTask(row)">删除</el-button></template></el-table-column>
          </el-table>
          <div class="table-note">管理员删除任务仅移除任务记录，不会影响其他用户数据。</div>
        </el-tab-pane>
        <el-tab-pane name="feedback">
          <span slot="label"><i class="el-icon-chat-line-square" /> 用户反馈</span>
          <el-table v-loading="feedbackLoading" :data="feedbacks" stripe empty-text="还没有收到反馈">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="username" label="来自" width="130" />
            <el-table-column prop="content" label="反馈内容" min-width="320" show-overflow-tooltip />
            <el-table-column prop="contact" label="联系方式" width="160" show-overflow-tooltip />
            <el-table-column label="时间" width="175"><template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template slot-scope="{ row }"><el-button type="text" class="danger-text" @click="removeFeedback(row)">删除</el-button></template></el-table-column>
          </el-table>
          <div class="table-note">来自帮助中心右上角「反馈与建议」，仅保存在本机。</div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog :title="userTaskTitle" :visible.sync="userTaskDialog" width="82%" top="6vh">
      <el-tabs v-model="userRecordTab">
        <el-tab-pane label="视频 / 音频任务" name="media">
          <el-table v-loading="userTasksLoading" :data="userTasks" stripe max-height="510" empty-text="该用户暂无视频或音频翻译记录">
            <el-table-column prop="id" label="ID" width="75" />
            <el-table-column label="翻译文件" min-width="230"><template slot-scope="{ row }"><b>{{ row.originalFilename || '未命名文件' }}</b><div class="record-meta">{{ row.mode || 'NORMAL' }} · {{ row.mediaType || '-' }}</div></template></el-table-column>
            <el-table-column label="语言" width="140"><template slot-scope="{ row }">{{ row.sourceLang || 'auto' }} → {{ row.targetLang || '-' }}</template></el-table-column>
            <el-table-column label="状态" width="120"><template slot-scope="{ row }"><el-tag size="mini" :type="taskTagType(row.status)">{{ taskStatusText(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="进度" width="130"><template slot-scope="{ row }"><el-progress :percentage="row.progress || 0" :stroke-width="6" /></template></el-table-column>
            <el-table-column label="创建时间" width="175"><template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="错误信息" min-width="180"><template slot-scope="{ row }"><span class="record-error">{{ row.errorMsg || '-' }}</span></template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="文本翻译" name="text">
          <el-table v-loading="userTasksLoading" :data="userTextTranslations" stripe max-height="510" empty-text="该用户暂无文本翻译记录">
            <el-table-column prop="id" label="ID" width="75" />
            <el-table-column prop="preview" label="原文摘要" min-width="300" show-overflow-tooltip />
            <el-table-column prop="targetLang" label="目标语言" width="120" />
            <el-table-column prop="model" label="模型" min-width="160" show-overflow-tooltip />
            <el-table-column label="耗时" width="110"><template slot-scope="{ row }">{{ row.elapsedMs == null ? '-' : `${row.elapsedMs} ms` }}</template></el-table-column>
            <el-table-column prop="untranslatedLines" label="未译行" width="90" />
            <el-table-column label="创建时间" width="175"><template slot-scope="{ row }">{{ formatTime(row.createdAt) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
      <span slot="footer"><el-button @click="userTaskDialog = false">关闭</el-button></span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'

export default {
  name: 'AdminDashboard',
  data () {
    return {
      activeTab: 'overview', loading: false, error: false,
      usersLoading: false, tasksLoading: false, concurrencyLoading: false, userTasksLoading: false, userKeyword: '', taskKeyword: '', taskStatus: 'ALL',
      users: [], tasks: [], concurrency: [], apiKeyword: '', userTasks: [], userTextTranslations: [], userRecordTab: 'media', userTaskDialog: false, selectedUser: null,
      feedbacks: [], feedbackLoading: false,
      summary: { totalUsers: 0, enabledUsers: 0, adminUsers: 0, totalTasks: 0, processingTasks: 0, failedTasks: 0, completedTasks: 0, todayTasks: 0 }
    }
  },
  computed: {
    filteredConcurrency () {
      const key = (this.apiKeyword || '').trim().toLowerCase()
      if (!key) return this.concurrency
      return this.concurrency.filter(row => `${row.method} ${row.path}`.toLowerCase().includes(key))
    },
    userTaskTitle () {
      if (!this.selectedUser) return '用户翻译记录'
      return `${this.selectedUser.nickname || this.selectedUser.username}（${this.selectedUser.username}）的翻译记录`
    },    statCards () {
      return [
        { label: '用户总数', value: this.summary.totalUsers, hint: `${this.summary.enabledUsers || 0} 个账户可用`, icon: 'el-icon-user', tone: 'blue' },
        { label: '翻译任务', value: this.summary.totalTasks, hint: `今日新增 ${this.summary.todayTasks || 0}`, icon: 'el-icon-tickets', tone: 'purple' },
        { label: '处理中', value: this.summary.processingTasks, hint: '实时任务队列', icon: 'el-icon-loading', tone: 'orange' },
        { label: '已完成', value: this.summary.completedTasks, hint: `失败 ${this.summary.failedTasks || 0} 个`, icon: 'el-icon-circle-check', tone: 'green' }
      ]
    },
    taskStatusCards () {
      return [
        { label: '已完成', value: this.summary.completedTasks, icon: 'el-icon-success', color: '#16b981' },
        { label: '处理中', value: this.summary.processingTasks, icon: 'el-icon-loading', color: '#f59e0b' },
        { label: '失败', value: this.summary.failedTasks, icon: 'el-icon-error', color: '#ef4444' }
      ]
    },
    healthPercentage () {
      if (!this.summary.totalTasks) return 100
      return Math.max(0, Math.min(100, Math.round((this.summary.completedTasks / this.summary.totalTasks) * 100)))
    }
  },
  mounted () { this.loadAll() },
  methods: {
    async loadAll () {
      this.loading = true; this.error = false
      try { const r = await http.get('/admin/summary'); this.summary = Object.assign(this.summary, r.data || {}); await Promise.all([this.loadUsers(), this.loadTasks(), this.loadConcurrency(), this.loadFeedback()]) } catch (e) { this.error = true } finally { this.loading = false }
    },
    async loadFeedback () {
      this.feedbackLoading = true
      try { const r = await http.get('/admin/feedback'); this.feedbacks = r.data || [] } catch (e) {} finally { this.feedbackLoading = false }
    },
    removeFeedback (row) {
      this.$confirm('删除这条反馈？', '提示', { type: 'warning' }).then(async () => {
        await http.delete(`/admin/feedback/${row.id}`)
        this.loadFeedback()
      }).catch(() => {})
    },
    async loadUsers () {
      this.usersLoading = true
      try { const r = await http.get('/admin/users', { params: { keyword: this.userKeyword || undefined } }); this.users = r.data || [] } catch (e) {} finally { this.usersLoading = false }
    },
    async loadTasks () {
      this.tasksLoading = true
      try { const r = await http.get('/admin/tasks', { params: { status: this.taskStatus || 'ALL', keyword: this.taskKeyword || undefined } }); this.tasks = r.data || [] } catch (e) {} finally { this.tasksLoading = false }
    },
    async loadConcurrency () {
      this.concurrencyLoading = true
      try { const r = await http.get('/admin/concurrency'); this.concurrency = r.data || [] } catch (e) {} finally { this.concurrencyLoading = false }
    },
    async viewUserTasks (row) {
      this.selectedUser = row
      this.userTaskDialog = true
      this.userTasksLoading = true
      this.userTasks = []
      this.userTextTranslations = []
      this.userRecordTab = 'media'
      try {
        const [media, text] = await Promise.all([
          http.get(`/admin/users/${row.id}/tasks`),
          http.get(`/admin/users/${row.id}/text-translations`)
        ])
        this.userTasks = media.data || []
        this.userTextTranslations = text.data || []
      } catch (e) {} finally { this.userTasksLoading = false }
    },
    async updateUser (row, payload) {
      try { await http.put(`/admin/users/${row.id}`, payload); this.$message.success('用户设置已更新'); await this.loadAll() } catch (e) { await this.loadUsers() }
    },
    removeTask (row) {
      this.$confirm(`确定删除任务「${row.originalFilename || row.id}」吗？`, '删除任务', { type: 'warning' }).then(async () => {
        await http.delete(`/admin/tasks/${row.id}`); this.$message.success('任务已删除'); await Promise.all([this.loadTasks(), this.loadAll()])
      }).catch(() => {})
    },
    avatarText (row) { return (row.nickname || row.username || '?').slice(0, 1).toUpperCase() },
    formatTime (value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' },
    taskStatusText (status) { return ({ DONE: '已完成', FAILED: '失败', PENDING: '排队中', PROCESSING: '处理中', BURNING: '合成中' })[status] || status || '未知' },
    taskTagType (status) { return status === 'DONE' ? 'success' : status === 'FAILED' ? 'danger' : status === 'PENDING' ? 'info' : 'warning' }
  }
}
</script>

<style scoped>
.admin-page { max-width: 1440px; margin: 0 auto; }
.admin-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.eyebrow { color: var(--brand-deep); font-size: 12px; font-weight: 700; letter-spacing: .08em; margin-bottom: 6px; }
.admin-alert { margin-bottom: 14px; }
.stat-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; margin-bottom: 16px; }
.stat-card { display: flex; align-items: center; min-height: 102px; }
.stat-card /deep/ .el-card__body { display: flex; align-items: center; width: 100%; padding: 18px; }
.stat-icon { width: 44px; height: 44px; border-radius: 13px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 20px; margin-right: 14px; }
.stat-icon.blue { background: linear-gradient(135deg, #60a5fa, #3b82f6); }.stat-icon.purple { background: linear-gradient(135deg, #a78bfa, #7c3aed); }.stat-icon.orange { background: linear-gradient(135deg, #fbbf24, #f97316); }.stat-icon.green { background: linear-gradient(135deg, #34d399, #059669); }
.stat-copy { display: flex; flex-direction: column; gap: 3px; }.stat-copy span, .stat-copy small { color: var(--text-sub); font-size: 12px; }.stat-copy strong { color: var(--text-main); font-size: 26px; line-height: 1.1; }
.admin-card { min-height: 430px; }.admin-card /deep/ .el-tabs__item { height: 48px; line-height: 48px; }.admin-card /deep/ .el-tabs__item i { margin-right: 5px; }
.overview-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; padding: 18px 4px; }.overview-panel { min-height: 190px; padding: 20px; border: 1px solid #eef1f6; border-radius: 12px; background: #fbfcff; }.panel-title { color: var(--text-main); font-size: 15px; font-weight: 700; margin-bottom: 18px; }.status-list { display: flex; flex-direction: column; gap: 15px; }.status-row { display: flex; align-items: center; justify-content: space-between; color: #64748b; font-size: 13px; }.status-row b { color: var(--text-main); font-size: 18px; }.status-row i { margin-right: 6px; }.health-score { display: flex; align-items: baseline; gap: 10px; margin: 24px 0 18px; }.health-score span { color: #16b981; font-size: 28px; font-weight: 700; }.health-score strong { color: #94a3b8; font-size: 13px; }.health-panel p { color: #8492a6; font-size: 12px; margin: 15px 0 0; line-height: 1.7; }
.toolbar { display: flex; gap: 10px; align-items: center; margin: 4px 0 14px; }.toolbar .el-input { width: 270px; }.toolbar .el-select { width: 150px; }.user-cell, .file-cell { display: flex; align-items: center; gap: 10px; }.user-cell small, .file-cell small { display: block; color: #94a3b8; font-size: 12px; margin-top: 3px; }.avatar { width: 32px; height: 32px; border-radius: 10px; display: inline-flex; align-items: center; justify-content: center; background: #eef2ff; color: var(--brand-deep); font-weight: 700; }.file-cell > i { color: var(--brand-deep); font-size: 20px; }.table-note { color: #94a3b8; font-size: 12px; padding-top: 12px; }.danger-text { color: #ef4444; }.active-concurrency { color: #f59e0b; }.record-meta { color: #94a3b8; font-size: 12px; margin-top: 4px; }.record-error { color: #ef4444; font-size: 12px; }
@media (max-width: 900px) { .stat-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.overview-grid { grid-template-columns: 1fr; }.toolbar { flex-wrap: wrap; } }
@media (max-width: 560px) { .stat-grid { grid-template-columns: 1fr; }.admin-head { align-items: flex-start; flex-direction: column; }.toolbar .el-input { width: 100%; } }
</style>
