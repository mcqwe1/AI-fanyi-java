<template>
  <div class="page">
    <div class="topbar">
      <el-button type="text" style="color:#fff" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      <span>AI 知识库翻译</span>
      <span />
    </div>
    <div class="body">
      <el-result icon="info" title="阶段3 功能"
                 subTitle="AI 观看视频建立知识库、结合知识库翻译并输出前情提要、多轮会话累积，将在普通模式稳定后实现。">
      </el-result>
    </div>
  </div>
</template>

<script>
export default { name: 'KbMode' }
</script>

<style scoped>
.topbar {
  height: 56px; background: #5b6ee1; color: #fff;
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; font-size: 18px;
}
.body { max-width: 900px; margin: 60px auto; }
</style>
