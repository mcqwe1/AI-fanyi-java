<template>
  <el-dialog title="字幕样式 / 烧录" :visible.sync="show" width="760px" top="6vh">
    <div class="style-body">
      <div class="controls">
        <el-form label-width="82px" size="small">
          <el-form-item label="字体">
            <el-select v-model="style.fontName" style="width:100%">
              <el-option label="微软雅黑" value="Microsoft YaHei" />
              <el-option label="黑体" value="SimHei" />
              <el-option label="宋体" value="SimSun" />
            </el-select>
          </el-form-item>
          <el-form-item label="字号">
            <el-slider v-model="style.fontSize" :min="20" :max="80" show-input :show-input-controls="false" />
          </el-form-item>
          <el-form-item label="字体颜色">
            <el-color-picker v-model="style.primaryColor" />
            <span class="hint">{{ style.primaryColor }}</span>
          </el-form-item>
          <el-form-item label="描边/框色">
            <el-color-picker v-model="style.outlineColor" />
            <span class="hint">{{ style.outlineColor }}</span>
          </el-form-item>
          <el-form-item label="描边宽度">
            <el-slider v-model="style.outline" :min="0" :max="6" :step="0.5" />
          </el-form-item>
          <el-form-item label="特效">
            <el-radio-group v-model="style.effect">
              <el-radio-button label="OUTLINE">描边</el-radio-button>
              <el-radio-button label="SHADOW">阴影</el-radio-button>
              <el-radio-button label="BOX">背景框</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="位置">
            <el-radio-group v-model="style.position">
              <el-radio-button label="TOP">顶部</el-radio-button>
              <el-radio-button label="MIDDLE">居中</el-radio-button>
              <el-radio-button label="BOTTOM">底部</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="加粗">
            <el-switch v-model="style.bold" />
          </el-form-item>
        </el-form>
      </div>

      <div class="preview">
        <div class="preview-label">
          实时预览
          <i v-if="previewing" class="el-icon-loading" />
        </div>
        <div class="preview-box" v-loading="previewing && !previewUrl" element-loading-text="生成预览中">
          <img v-if="previewUrl" :src="previewUrl" alt="预览" :class="{ dim: previewing }" />
          <div v-else class="placeholder">
            <i class="el-icon-picture-outline" style="font-size:40px" /><br />
            调整左侧样式，预览会自动刷新
          </div>
          <div v-if="previewing && previewUrl" class="spinner"><i class="el-icon-loading" /></div>
        </div>
        <div class="tip">从视频抽一帧 + 烧样例字幕，所见即所得</div>
      </div>
    </div>

    <span slot="footer">
      <el-button @click="show = false">取消</el-button>
      <el-button type="primary" :loading="burning" icon="el-icon-film" @click="doBurn">生成烧录视频</el-button>
    </span>
  </el-dialog>
</template>

<script>
import http from '../api/http'

function defaultStyle () {
  return {
    fontName: 'Microsoft YaHei',
    fontSize: 42,
    primaryColor: '#FFEB3B',
    outlineColor: '#000000',
    outline: 2,
    effect: 'OUTLINE',
    position: 'BOTTOM',
    bold: true
  }
}

export default {
  name: 'StyleDialog',
  props: {
    visible: { type: Boolean, default: false },
    taskId: { type: [Number, String], default: null }
  },
  data () {
    return {
      style: defaultStyle(),
      previewUrl: '',
      previewing: false,
      burning: false,
      debounceTimer: null,
      reqSeq: 0
    }
  },
  computed: {
    show: {
      get () { return this.visible },
      set (v) { this.$emit('update:visible', v) }
    }
  },
  watch: {
    visible (v) {
      if (v) {
        this.clearPreview()
        // 打开即自动出一张预览
        this.$nextTick(() => this.doPreview())
      } else {
        clearTimeout(this.debounceTimer)
      }
    },
    style: {
      deep: true,
      handler () {
        if (this.visible) this.schedulePreview()
      }
    }
  },
  beforeDestroy () {
    clearTimeout(this.debounceTimer)
    this.clearPreview()
  },
  methods: {
    clearPreview () {
      if (this.previewUrl) URL.revokeObjectURL(this.previewUrl)
      this.previewUrl = ''
    },
    schedulePreview () {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = setTimeout(() => this.doPreview(), 300)
    },
    async doPreview () {
      if (!this.taskId) return
      const seq = ++this.reqSeq
      this.previewing = true
      try {
        const resp = await http.post(`/tasks/${this.taskId}/style/preview`, this.style, { responseType: 'blob' })
        if (seq !== this.reqSeq) return // 已有更新的请求，丢弃过期结果
        const url = URL.createObjectURL(resp.data)
        if (this.previewUrl) URL.revokeObjectURL(this.previewUrl)
        this.previewUrl = url
      } catch (e) { /* 拦截器已提示 */ } finally {
        if (seq === this.reqSeq) this.previewing = false
      }
    },
    async doBurn () {
      if (!this.taskId) return
      this.burning = true
      try {
        await http.post(`/tasks/${this.taskId}/burn`, this.style)
        this.$message.success('已开始烧录，可在任务列表看进度')
        this.show = false
        this.$emit('burned')
      } catch (e) { /* ignore */ } finally { this.burning = false }
    }
  }
}
</script>

<style scoped>
.style-body { display: flex; gap: 20px; }
.controls { width: 360px; flex-shrink: 0; }
.preview { flex: 1; display: flex; flex-direction: column; }
.preview-label { font-size: 13px; color: #666; margin-bottom: 6px; }
.preview-box {
  position: relative;
  flex: 1; min-height: 220px; background: #1e1e1e; border-radius: 6px;
  display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.preview-box img { width: 100%; display: block; transition: opacity .15s; }
.preview-box img.dim { opacity: .55; }
.placeholder { color: #888; text-align: center; line-height: 1.8; font-size: 13px; }
.spinner {
  position: absolute; top: 8px; right: 10px; color: #fff; font-size: 20px;
  text-shadow: 0 0 4px rgba(0,0,0,.8);
}
.hint { color: #999; font-size: 12px; margin-left: 8px; }
.tip { color: #999; font-size: 12px; margin-top: 8px; text-align: center; }
</style>
