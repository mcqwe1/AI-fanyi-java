<template>
  <div class="home">
    <div class="topbar">
      <span>AI 视频翻译</span>
      <span class="right">
        <i class="el-icon-user" /> {{ username }}
        <el-button type="text" style="color:#fff;margin-left:12px" icon="el-icon-setting"
                   @click="$router.push('/settings')">设置</el-button>
        <el-button type="text" style="color:#fff;margin-left:12px" @click="logout">退出</el-button>
      </span>
    </div>
    <div class="cards">
      <el-card class="mode-card" shadow="hover" @click.native="$router.push('/normal')">
        <i class="el-icon-video-camera mode-icon" />
        <h3>普通 AI 视频翻译</h3>
        <p>上传视频 → 提取音频 → 语音转文字 → AI 翻译 → 输出字幕 / 烧录</p>
      </el-card>
      <el-card class="mode-card" shadow="hover" @click.native="$router.push('/kb')">
        <i class="el-icon-collection mode-icon" />
        <h3>AI 知识库翻译</h3>
        <p>AI 先观看视频建立知识库，结合知识库翻译并输出前情提要（阶段3）</p>
      </el-card>
      <el-card class="mode-card" shadow="hover" @click.native="$router.push('/text')">
        <i class="el-icon-document mode-icon" />
        <h3>AI 文本翻译</h3>
        <p>粘贴任意文本 → 即时翻译，历史可导出 txt / 双语对照</p>
      </el-card>
    </div>
  </div>
</template>

<script>
export default {
  name: 'Home',
  computed: {
    username () { return this.$store.state.username }
  },
  methods: {
    logout () {
      this.$store.commit('logout')
      this.$router.push('/login')
    }
  }
}
</script>

<style scoped>
.topbar {
  height: 56px; background: #5b6ee1; color: #fff;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; font-size: 18px;
}
.cards { display: flex; gap: 24px; justify-content: center; margin-top: 60px; flex-wrap: wrap; }
.mode-card { width: 340px; cursor: pointer; text-align: center; padding: 20px; }
.mode-icon { font-size: 56px; color: #5b6ee1; }
.mode-card h3 { margin: 16px 0 8px; }
.mode-card p { color: #888; font-size: 13px; }
</style>
