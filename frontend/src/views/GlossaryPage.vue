<template>
  <div class="page">
    <div class="page-head">
      <h2>术语库管理</h2>
      <p>集中管理所有系列项目的术语：增删改查、启用开关，勾选后可批量移动 / 复制到其他系列项目、批量删除。</p>
    </div>

    <div class="body">
      <!-- 筛选与操作条 -->
      <el-card class="panel">
        <div class="toolbar">
          <el-select v-model="projectFilter" style="width:220px" @change="loadTerms">
            <el-option label="全部项目" value="all" />
            <el-option v-for="p in projects" :key="p.id"
                       :label="`${p.name}（${p.termCount}词）`" :value="p.id" />
          </el-select>
          <el-input v-model="kw" placeholder="搜索原文 / 译法 / 说明" prefix-icon="el-icon-search"
                    clearable style="width:220px" />
          <el-select v-model="catFilter" placeholder="类别" clearable style="width:140px">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
          <el-select v-model="originFilter" placeholder="来源" clearable style="width:110px">
            <el-option label="自动" value="auto" />
            <el-option label="人工" value="manual" />
          </el-select>
          <span class="spacer" />
          <el-button type="primary" icon="el-icon-plus" size="small" @click="openAdd">新增术语</el-button>
          <el-button icon="el-icon-folder-add" size="small" @click="newProjVisible = true">新建项目</el-button>
          <el-button v-if="projectFilter !== 'all'" icon="el-icon-delete" size="small" class="del"
                     @click="removeProject">删除项目</el-button>
        </div>
      </el-card>

      <!-- 术语表 -->
      <el-card class="panel">
        <div v-if="selection.length" class="batch-bar">
          <span>已选 <b>{{ selection.length }}</b> 条</span>
          <el-button size="mini" type="primary" plain icon="el-icon-right" @click="openTransfer('move')">移动到…</el-button>
          <el-button size="mini" type="primary" plain icon="el-icon-document-copy" @click="openTransfer('copy')">复制到…</el-button>
          <el-button size="mini" type="danger" plain icon="el-icon-delete" @click="batchRemove">批量删除</el-button>
        </div>
        <el-table :data="filteredTerms" size="small" stripe v-loading="loading"
                  @selection-change="s => selection = s">
          <el-table-column type="selection" width="42" />
          <el-table-column prop="sourceTerm" label="原文" min-width="140" show-overflow-tooltip />
          <el-table-column prop="targetTerm" label="译法" min-width="140" show-overflow-tooltip />
          <el-table-column prop="category" label="类别" width="130">
            <template slot-scope="{ row }">{{ row.category || '—' }}</template>
          </el-table-column>
          <el-table-column prop="note" label="说明" min-width="160" show-overflow-tooltip>
            <template slot-scope="{ row }">{{ row.note || '—' }}</template>
          </el-table-column>
          <el-table-column label="来源" width="70">
            <template slot-scope="{ row }">
              <el-tag size="mini" :type="row.origin === 'auto' ? 'warning' : 'info'">
                {{ row.origin === 'auto' ? '自动' : '人工' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="60">
            <template slot-scope="{ row }">
              <el-switch :value="row.enabled" :active-value="1" :inactive-value="0"
                         @change="v => toggleEnabled(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="所属项目" width="150" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <el-tag size="mini" effect="plain">{{ row.projectName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template slot-scope="{ row }">
              <el-button type="text" @click="openEdit(row)">编辑</el-button>
              <el-button type="text" class="del" @click="removeOne(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !filteredTerms.length"
                  :description="projects.length ? '没有符合条件的术语' : '还没有系列项目，先新建一个'" :image-size="80" />
      </el-card>
    </div>

    <!-- 新增/编辑术语弹窗 -->
    <el-dialog :title="termForm.id ? '编辑术语' : '新增术语'" :visible.sync="termVisible" width="460px">
      <el-form label-width="80px">
        <el-form-item v-if="!termForm.id" label="所属项目">
          <el-select v-model="termForm.projectId" style="width:100%">
            <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="原文">
          <el-input v-model="termForm.sourceTerm" maxlength="200" placeholder="原文（必填）" />
        </el-form-item>
        <el-form-item label="译法">
          <el-input v-model="termForm.targetTerm" maxlength="200" placeholder="指定译法（必填）" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="termForm.category" placeholder="类别" filterable allow-create clearable style="width:100%">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="termForm.note" maxlength="500" placeholder="依据 / 备注" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="termForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="termVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTerm" @click="saveTerm">保存</el-button>
      </span>
    </el-dialog>

    <!-- 批量移动/复制弹窗 -->
    <el-dialog :title="transferMode === 'move' ? '批量移动术语' : '批量复制术语'"
               :visible.sync="transferVisible" width="420px">
      <p class="dlg-tip">
        已选 {{ selection.length }} 条术语，{{ transferMode === 'move' ? '将移动到' : '将复制到' }}下面选择的项目
        {{ transferMode === 'move' ? '（原项目不再保留）' : '（原项目保留）' }}；已在目标项目中的术语会跳过。
      </p>
      <el-select v-model="transferTarget" placeholder="选择目标项目" style="width:100%">
        <el-option v-for="p in projects" :key="p.id"
                   :label="`${p.name}（${p.sourceLang}→${p.targetLang}）`" :value="p.id" />
      </el-select>
      <span slot="footer">
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="doTransfer">
          {{ transferMode === 'move' ? '移动' : '复制' }}
        </el-button>
      </span>
    </el-dialog>

    <!-- 新建项目弹窗 -->
    <el-dialog title="新建系列项目" :visible.sync="newProjVisible" width="420px">
      <el-form label-width="80px">
        <el-form-item label="项目名"><el-input v-model="newProj.name" placeholder="如：本多频道" /></el-form-item>
        <el-form-item label="源语言">
          <el-select v-model="newProj.sourceLang" filterable style="width:100%">
            <el-option label="自动检测" value="auto" />
            <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
              <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
            </el-option-group>
          </el-select>
        </el-form-item>
        <el-form-item label="目标语言">
          <el-select v-model="newProj.targetLang" filterable style="width:100%">
            <el-option-group v-for="g in langGroups" :key="g.label" :label="g.label">
              <el-option v-for="l in g.langs" :key="l" :label="l" :value="l" />
            </el-option-group>
          </el-select>
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="newProjVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingProj" @click="createProject">创建</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { LANG_GROUPS } from '../constants/langs'
import { TERM_CATEGORIES } from '../constants/kb'

export default {
  name: 'GlossaryPage',
  data () {
    return {
      categories: TERM_CATEGORIES,
      langGroups: LANG_GROUPS,
      projects: [],
      projectFilter: 'all',
      terms: [],
      loading: false,
      kw: '',
      catFilter: '',
      originFilter: '',
      selection: [],
      // 术语弹窗
      termVisible: false,
      savingTerm: false,
      termForm: { id: null, projectId: null, sourceTerm: '', targetTerm: '', category: '', note: '', enabled: 1 },
      // 移动/复制弹窗
      transferVisible: false,
      transferMode: 'move',
      transferTarget: null,
      transferring: false,
      // 新建项目弹窗
      newProjVisible: false,
      creatingProj: false,
      newProj: { name: '', sourceLang: 'auto', targetLang: '中文' }
    }
  },
  computed: {
    filteredTerms () {
      const kw = this.kw.trim().toLowerCase()
      return this.terms.filter(t => {
        if (this.catFilter && t.category !== this.catFilter) return false
        if (this.originFilter && t.origin !== this.originFilter) return false
        if (kw) {
          const hay = `${t.sourceTerm || ''}\n${t.targetTerm || ''}\n${t.note || ''}`.toLowerCase()
          if (!hay.includes(kw)) return false
        }
        return true
      })
    }
  },
  mounted () {
    this.reload()
  },
  methods: {
    async reload () {
      await this.loadProjects()
      // 当前筛选的项目被删掉时回退到全部
      if (this.projectFilter !== 'all' && !this.projects.some(p => p.id === this.projectFilter)) {
        this.projectFilter = 'all'
      }
      await this.loadTerms()
    },
    async loadProjects () {
      try {
        const r = await http.get('/kb/projects')
        this.projects = r.data || []
      } catch (e) { /* 拦截器已提示 */ }
    },
    async loadTerms () {
      this.loading = true
      try {
        const list = this.projectFilter === 'all'
          ? this.projects
          : this.projects.filter(p => p.id === this.projectFilter)
        const chunks = await Promise.all(list.map(p =>
          http.get(`/kb/projects/${p.id}/terms`)
            .then(r => (r.data || []).map(t => ({ ...t, projectId: p.id, projectName: p.name })))
            .catch(() => [])
        ))
        this.terms = chunks.flat()
      } finally { this.loading = false }
    },
    /* ---------- 增改 ---------- */
    openAdd () {
      if (!this.projects.length) return this.$message.warning('请先新建一个系列项目')
      this.termForm = {
        id: null,
        projectId: this.projectFilter !== 'all' ? this.projectFilter : this.projects[0].id,
        sourceTerm: '', targetTerm: '', category: '', note: '', enabled: 1
      }
      this.termVisible = true
    },
    openEdit (row) {
      this.termForm = {
        id: row.id, projectId: row.projectId,
        sourceTerm: row.sourceTerm, targetTerm: row.targetTerm,
        category: row.category || '', note: row.note || '', enabled: row.enabled
      }
      this.termVisible = true
    },
    async saveTerm () {
      const f = this.termForm
      if (!f.sourceTerm.trim() || !f.targetTerm.trim()) return this.$message.warning('原文和译法都要填')
      this.savingTerm = true
      try {
        await http.put(`/kb/projects/${f.projectId}/terms`, {
          terms: [{ id: f.id, sourceTerm: f.sourceTerm, targetTerm: f.targetTerm,
                    category: f.category, note: f.note, enabled: f.enabled }]
        })
        this.$message.success(f.id ? '已保存' : '已新增')
        this.termVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally { this.savingTerm = false }
    },
    async toggleEnabled (row, v) {
      try {
        await http.put(`/kb/projects/${row.projectId}/terms`, {
          terms: [{ id: row.id, sourceTerm: row.sourceTerm, targetTerm: row.targetTerm,
                    category: row.category, note: row.note, enabled: v }]
        })
        row.enabled = v
      } catch (e) { /* 拦截器已提示 */ }
    },
    /* ---------- 删 ---------- */
    removeOne (row) {
      this.$confirm(`删除术语「${row.sourceTerm} → ${row.targetTerm}」？`, '删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/kb/terms/${row.id}`)
          this.$message.success('已删除')
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    batchRemove () {
      this.$confirm(`删除选中的 ${this.selection.length} 条术语？不可恢复。`, '批量删除', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          const r = await http.post('/kb/terms/batch-delete', { termIds: this.selection.map(t => t.id) })
          this.$message.success(`已删除 ${r.data.count} 条`)
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    /* ---------- 移动/复制 ---------- */
    openTransfer (mode) {
      if (this.projects.length < 2) return this.$message.warning('至少需要两个系列项目才能移动/复制')
      this.transferMode = mode
      this.transferTarget = null
      this.transferVisible = true
    },
    async doTransfer () {
      if (!this.transferTarget) return this.$message.warning('请选择目标项目')
      this.transferring = true
      try {
        const r = await http.post('/kb/terms/transfer', {
          termIds: this.selection.map(t => t.id),
          targetProjectId: this.transferTarget,
          mode: this.transferMode
        })
        this.$message.success(`${this.transferMode === 'move' ? '已移动' : '已复制'} ${r.data.count} 条`)
        this.transferVisible = false
        this.reload()
      } catch (e) { /* 拦截器已提示 */ } finally { this.transferring = false }
    },
    /* ---------- 项目 ---------- */
    async createProject () {
      if (!this.newProj.name.trim()) return this.$message.warning('请填项目名')
      this.creatingProj = true
      try {
        const r = await http.post('/kb/projects', this.newProj)
        this.$message.success('已创建')
        this.newProjVisible = false
        this.newProj = { name: '', sourceLang: 'auto', targetLang: '中文' }
        await this.loadProjects()
        this.projectFilter = r.data.projectId
        this.loadTerms()
      } catch (e) { /* 拦截器已提示 */ } finally { this.creatingProj = false }
    },
    removeProject () {
      const p = this.projects.find(x => x.id === this.projectFilter)
      if (!p) return
      this.$confirm(`删除系列项目「${p.name}」及其全部 ${p.termCount} 条术语？任务记录不受影响。`, '删除项目', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/kb/projects/${p.id}`)
          this.$message.success('已删除')
          this.projectFilter = 'all'
          this.reload()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.body { max-width: 1100px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.toolbar .spacer { flex: 1; }
.batch-bar {
  display: flex; align-items: center; gap: 10px; margin-bottom: 12px;
  padding: 8px 12px; background: #eef2ff; border-radius: 8px; font-size: 13px;
}
.dlg-tip { margin: 0 0 14px; color: #666; font-size: 13px; line-height: 1.6; }
</style>
