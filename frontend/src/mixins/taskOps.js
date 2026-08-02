import http from '../api/http'

/**
 * 任务「重试 / 删除」确认框 + 请求，各任务列表页共用。
 * onDone 传各页面自己的刷新方法（fetchTasks / loadHistory）。
 */
export default {
  methods: {
    retryTask (row, onDone) {
      this.$confirm(`重跑任务「${row.originalFilename || row.id}」？将清除现有字幕、复用已上传文件重新处理。`, '重试', {
        confirmButtonText: '重试', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.post(`/tasks/${row.id}/retry`)
          this.$message.success('已重新提交')
          if (onDone) onDone()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    },
    removeTask (row, onDone) {
      this.$confirm(`确定删除任务「${row.originalFilename || row.id}」吗？字幕与文件将一并清除，不可恢复。`, '删除任务', {
        confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
      }).then(async () => {
        try {
          await http.delete(`/tasks/${row.id}`)
          this.$message.success('已删除')
          if (onDone) onDone()
        } catch (e) { /* 拦截器已提示 */ }
      }).catch(() => {})
    }
  }
}
