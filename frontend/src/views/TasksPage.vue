<template>
  <div>
    <div class="page-head">
      <h2>我的任务</h2>
      <p>全部模式的翻译任务都在这里；完成后可下载 SRT / 译文 TXT、编辑字幕轴、烧录视频。</p>
    </div>

    <el-card>
      <div class="toolbar">
        <el-radio-group v-model="filter" size="small">
          <el-radio-button label="all">全部</el-radio-button>
          <el-radio-button label="running">进行中</el-radio-button>
          <el-radio-button label="done">已完成</el-radio-button>
          <el-radio-button label="failed">失败</el-radio-button>
        </el-radio-group>
        <span class="hint">共 {{ filtered.length }} 个任务，自动刷新</span>
      </div>

      <el-table :data="filtered" size="small" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="originalFilename" label="文件" min-width="180" show-overflow-tooltip />
        <el-table-column label="模式" width="90">
          <template slot-scope="{ row }">
            <el-tag size="mini" effect="plain" :type="row.mode === 'KB' ? 'success' : ''">
              {{ row.mode === 'KB' ? '术语库' : '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="80">
          <template slot-scope="{ row }">
            <el-tag size="mini" effect="plain" :type="row.mediaType === 'AUDIO' ? 'warning' : ''">
              {{ row.mediaType === 'AUDIO' ? '音频' : '视频' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetLang" label="目标语言" width="90" />
        <el-table-column label="状态" width="190">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="tagType(row.status)">{{ statusText(row.status) }}</el-tag>
            <el-progress v-if="!isFinal(row.status)" :percentage="row.progress || 0" :stroke-width="6" />
            <div v-if="row.status === 'FAILED'" class="err">{{ row.errorMsg }}</div>
            <!-- 配音是独立于主任务的附加状态 -->
            <div v-if="row.dubStatus === 'DUBBING'" class="dub-line">
              <el-tag size="mini" type="warning" effect="plain">配音中 {{ row.dubProgress || 0 }}%</el-tag>
            </div>
            <div v-else-if="row.dubStatus === 'FAILED'" class="dub-line">
              <el-tag size="mini" type="danger" effect="plain">配音失败</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="560">
          <template slot-scope="{ row }">
            <el-button v-if="row.status === 'DONE'" type="text" icon="el-icon-edit-outline"
                       @click="$router.push(`/editor/${row.id}`)">编辑字幕</el-button>
            <el-button v-if="row.status === 'DONE'" type="text" @click="download(row.id)">SRT</el-button>
            <el-button v-if="row.status === 'DONE'" type="text" @click="downloadTxt(row.id)">TXT</el-button>
            <el-button v-if="row.mediaType !== 'AUDIO' && (row.status === 'DONE' || row.status === 'BURNING')"
                       type="text" @click="openStyle(row.id)">烧录</el-button>
            <el-button v-if="row.mediaType !== 'AUDIO' && row.status === 'DONE'"
                       type="text" @click="$router.push(`/dub/${row.id}`)">
              {{ row.dubStatus === 'DUBBING' ? '配音中…' : (row.hasDub ? '重新配音' : '配音') }}
            </el-button>
            <el-button v-if="row.hasVideo" type="text" @click="downloadVideo(row.id)">下载视频</el-button>
            <el-button v-if="row.hasDub" type="text" @click="downloadDub(row.id)">下载配音</el-button>
            <el-button v-if="isFinal(row.status)" type="text" @click="retry(row)">重试</el-button>
            <el-button type="text" class="del" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!filtered.length" description="没有符合条件的任务" :image-size="80" />
    </el-card>

    <style-dialog :visible.sync="styleVisible" :task-id="styleTaskId" @burned="fetchTasks" />
  </div>
</template>

<script>
import http from '../api/http'
import StyleDialog from '../components/StyleDialog.vue'
import { statusText, isFinal, tagType } from '../constants/status'
import { blobDown } from '../utils/download'
import taskOps from '../mixins/taskOps'

export default {
  name: 'TasksPage',
  components: { StyleDialog },
  mixins: [taskOps],
  data () {
    return {
      tasks: [],
      filter: 'all',
      timer: null,
      styleVisible: false,
      styleTaskId: null
    }
  },
  computed: {
    filtered () {
      if (this.filter === 'running') return this.tasks.filter(t => !this.isFinal(t.status))
      if (this.filter === 'done') return this.tasks.filter(t => t.status === 'DONE')
      if (this.filter === 'failed') return this.tasks.filter(t => t.status === 'FAILED')
      return this.tasks
    }
  },
  mounted () {
    this.fetchTasks()
    this.timer = setInterval(this.fetchTasks, 2000)
  },
  beforeDestroy () { clearInterval(this.timer) },
  methods: {
    statusText, isFinal, tagType,
    fetchTasks () {
      http.get('/tasks').then(r => { this.tasks = r.data || [] }).catch(() => {})
    },
    download (id) { blobDown(`/tasks/${id}/srt`, `subtitle-${id}.srt`) },
    downloadTxt (id) { blobDown(`/tasks/${id}/txt`, `transcript-${id}.txt`) },
    downloadVideo (id) { blobDown(`/tasks/${id}/video`, `burned-${id}.mp4`) },
    downloadDub (id) { blobDown(`/tasks/${id}/dub-video`, `dubbed-${id}.mp4`) },
    openStyle (id) {
      this.styleTaskId = id
      this.styleVisible = true
    },
    retry (row) { this.retryTask(row, this.fetchTasks) },
    remove (row) { this.removeTask(row, this.fetchTasks) }
  }
}
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.dub-line { margin-top: 4px; }
</style>
