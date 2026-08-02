<template>
  <div class="page">
    <div class="topbar">
      <el-button type="text" style="color:#fff" icon="el-icon-back" @click="back">返回</el-button>
      <span>使用教程</span>
      <span />
    </div>

    <div class="body">
      <el-card class="panel">
        <div slot="header"><b>三分钟上手</b></div>
        <ol class="steps">
          <li>登录后进入「<b>设置 → API 密钥</b>」，填好两把钥匙：<b>Groq/本地模型不需要</b>（语音识别）和 <b>AI模型</b>（AI 翻译，推荐 DeepSeek）。</li>
          <li>回首页选「<b>普通 AI 翻译</b>」，上传视频（mp4/mkv/mov…）或音频（mp3/wav/m4a…）。</li>
          <li>选目标语言，点「开始翻译」，等进度条走完。</li>
          <li>完成后可：<b>下载 SRT</b> 字幕 / <b>译文 TXT</b> / 「字幕样式/烧录」把字幕烧进视频。</li>
          <li>对结果不满意可点「重试」换识别引擎或语言重跑，不用重新上传。</li>
        </ol>
      </el-card>

      <el-card class="panel">
        <div slot="header"><b>获取 Groq Key（语音识别，免费，需要魔法，还需要注意一点Groq的免费层级一次只能处理不到2个小时的视频/音频）</b></div>
        <ol class="steps">
          <li>打开 <a href="https://console.groq.com/keys" target="_blank" rel="noopener">console.groq.com/keys</a>。<b>注意：国内网络通常需要代理</b>才能访问 Groq（注册和翻译时都是）。</li>
          <li>用邮箱或 Google 账号注册登录。</li>
          <li>点 <b>Create API Key</b>，起个名字，复制 <code>gsk_</code> 开头的 key（只显示一次）。</li>
          <li>粘贴到本软件「设置 → API 密钥 → Groq API Key」，保存。</li>
        </ol>
        <p class="tip">无法访问 Groq？改用「本地 Whisper」（见下方可选组件），完全离线、免 key。</p>
      </el-card>

      <el-card class="panel">
        <div slot="header"><b>获取 DeepSeek Key（不只限制DeepSeek，可以使用其他AI模型进行翻译）</b></div>
        <ol class="steps">
          <li>打开 <a href="https://platform.deepseek.com" target="_blank" rel="noopener">platform.deepseek.com</a>，手机号注册。</li>
          <li>充值少量金额。</li>
          <li>「API Keys」页创建并复制 <code>sk-</code> 开头的 key。</li>
          <li>填到「设置 → API 密钥」：接口地址 <code>https://api.deepseek.com</code>、API Key、模型 <code>deepseek-v4</code>。</li>
        </ol>
        <p class="tip">也支持任何 OpenAI 兼容接口（通义 / 智谱 / 中转站…），换地址和模型名即可。</p>
      </el-card>

      <el-card class="panel">
        <div slot="header"><b>常见问题</b></div>
        <ul class="steps">
          <li><b>双击 start.bat 被 Windows 拦截</b>：SmartScreen 提示时点「更多信息 → 仍要运行」；杀毒软件误报请加白名单。</li>
          <li><b>端口被占用</b>：用 <code>start.bat 9090</code> 换端口启动，浏览器访问 <code>localhost:9090</code>。</li>
          <li><b>翻译失败提示密钥错误</b>：检查 key 是否复制完整、账户是否有余额；Groq 报网络错误多为代理未开。</li>
          <li><b>数据在哪 / 怎么搬家</b>：全部在软件目录的 <code>data\</code> 里（数据库+视频+字幕），拷走这个文件夹即完成备份迁移。</li>
          <li><b>安全</b>：服务只监听本机，密钥存在本机数据库；不要把 <code>data\</code> 目录发给别人。</li>
        </ul>
      </el-card>
    </div>
  </div>
</template>

<script>
export default {
  name: 'Guide',
  methods: {
    back () {
      // 未登录时从登录页链接进来，返回登录页；已登录返回首页
      this.$router.push(localStorage.getItem('token') ? '/' : '/login')
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
.body { max-width: 860px; margin: 24px auto; display: flex; flex-direction: column; gap: 16px; padding: 0 16px 40px; }
.steps { padding-left: 20px; line-height: 2; margin: 0; font-size: 14px; }
.panel a { color: #5b6ee1; }
.tip { color: #999; font-size: 12px; margin: 10px 0 0; }
code { background: #f4f4f5; padding: 1px 6px; border-radius: 3px; font-size: 12px; }
</style>
