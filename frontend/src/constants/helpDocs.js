// 帮助中心（文档中心）内容。纯前端数据：与手册 help/manual.md 同源整理，
// 描述的是当前版本的真实功能——改功能时记得同步这里和 manual.md。
// html 字段用受控内容（本文件手写），HelpCenter 用 v-html 渲染。

export const HELP_CATEGORIES = [
  { id: 'start', name: '开始使用', icon: 'el-icon-s-home', desc: '新手指南、快速上手、功能概览' },
  { id: 'account', name: '账户与设置', icon: 'el-icon-user', desc: '账号管理、API 配置、模型设置' },
  { id: 'core', name: '核心功能', icon: 'el-icon-video-camera', desc: '翻译功能、术语库、字幕导出' },
  { id: 'advanced', name: '进阶使用', icon: 'el-icon-magic-stick', desc: '全能AI翻译、划词翻译、翻译风格' },
  { id: 'faq', name: '常见问题', icon: 'el-icon-question', desc: '问题解答、故障排除' },
  { id: 'changelog', name: '更新日志', icon: 'el-icon-time', desc: '版本更新、功能改进' }
]

/** 首页「推荐阅读」卡片（按 id 引用下方文档） */
export const RECOMMENDED = [
  { doc: 'quick-start', tag: '新手必看', tagType: 'danger', minutes: 3 },
  { doc: 'api-config', tag: '热门', tagType: 'warning', minutes: 8 },
  { doc: 'video', tag: '进阶', tagType: 'primary', minutes: 15 },
  { doc: 'glossary', tag: '推荐', tagType: 'success', minutes: 8 }
]

/** 首页「最近更新」列表 */
export const RECENT_UPDATES = [
  { doc: 'changelog', title: 'V2.1 设置中心改版 · 品牌更名「狐译」', date: '2026-08-13', tag: '新功能' },
  { doc: 'changelog', title: 'V2.0 多智能体质量增强系统 + 便携分发版', date: '2026-08-01', tag: '新功能' },
  { doc: 'selection', title: '划词翻译浏览器扩展使用指南', date: '2026-08-05', tag: '教程' },
  { doc: 'faq', title: 'API 配置常见问题解答', date: '2026-08-13', tag: 'FAQ' }
]

export const HELP_DOCS = [
  // ==================== 开始使用 ====================
  {
    id: 'welcome',
    cat: 'start',
    title: '欢迎使用狐译',
    minutes: 3,
    updated: '2026-08-13',
    sections: [
      {
        id: 'what',
        h: '什么是狐译？',
        html: `<p>狐译是一款<b>本地运行</b>的 AI 翻译工作台：把视频/音频转写并翻译成目标语言字幕，也能翻译文本、
文档和网页划词内容。所有数据保存在你自己的电脑上，AI 能力通过你自己配置的 API 密钥调用。</p>`
      },
      {
        id: 'features',
        h: '主要功能',
        html: `<ul>
<li><b>音频/视频翻译</b>：上传媒体文件 → 语音识别 → AI 翻译 → 导出 SRT 字幕或烧录进视频；</li>
<li><b>全能AI翻译</b>：多智能体协作（读稿 → 领域专家 → 联网核实 → 仲裁），追求最高质量；</li>
<li><b>文本 / 文档AI翻译</b>：纯文本与 docx/pdf/xlsx/pptx 等 12 种格式的文档翻译，支持对照预览；</li>
<li><b>划词翻译</b>：浏览器扩展，网页上选中文字/图片/整页即可翻译；</li>
<li><b>AI 配音（TTS）</b>：把译文合成为配音音轨；</li>
<li><b>字幕编辑器</b>：逐行校对、改时间轴、样式与烧录。</li>
</ul>`
      },
      {
        id: 'scene',
        h: '适用场景',
        html: `<p>看外语课程/教程/访谈、追没有字幕的生肉视频、做视频本地化、翻译外文资料与合同文档、
玩外服游戏或读外文论坛（配合划词翻译）。</p>`
      },
      {
        id: 'require',
        h: '系统要求',
        html: `<p>Windows 10/11 64 位。便携版解压即用（内置 Java/Python/FFmpeg），双击 <code>start.bat</code> 启动，
浏览器访问 <code>http://localhost:8080</code>。识别与翻译速度取决于你的网络和所选模型。</p>`
      }
    ]
  },
  {
    id: 'quick-start',
    cat: 'start',
    title: '三分钟上手',
    minutes: 3,
    updated: '2026-08-13',
    sections: [
      {
        id: 'step1',
        h: '第一步：注册与登录',
        html: `<p>双击 <code>start.bat</code> 启动后打开浏览器访问 <code>localhost:8080</code>。
第一个注册的账号自动成为管理员。</p>`
      },
      {
        id: 'step2',
        h: '第二步：配置模型服务',
        html: `<p>进入「<b>设置 → API 配置 → 大语言模型</b>」，在服务商下拉里选一家（推荐 DeepSeek，国内直连、价格低），
填入 API Key，点「拉取模型」选择模型，最后<b>保存服务</b>。第一条服务会自动设为默认。</p>
<p>语音识别默认用<b>内置本地 Whisper</b>，零配置；想更快可以在「语音识别」面板配一个免费的 Groq Key（需魔法）。</p>`
      },
      {
        id: 'step3',
        h: '第三步：翻译第一个视频',
        html: `<ol>
<li>回工作台，点「音频/视频翻译」，上传 mp4/mkv/mp3/wav 等文件；</li>
<li>选目标语言和识别引擎，点「开始翻译」；</li>
<li>等进度条走完，下载 SRT 字幕或译文 TXT，也可以进字幕编辑器校对、烧录。</li>
</ol>
<p>对结果不满意可以点「重试」换识别引擎或模型重跑，不用重新上传。</p>`
      }
    ]
  },
  {
    id: 'features-tour',
    cat: 'start',
    title: '功能概览（五种翻译模式怎么选）',
    minutes: 5,
    updated: '2026-08-13',
    sections: [
      {
        id: 'modes',
        h: '四种翻译模式',
        html: `<ul>
<li><b>音频/视频翻译</b>——最常用。识别 + 翻译一条龙，速度快，适合大多数内容；</li>
<li><b>全能AI翻译</b>——多智能体流水线（场景推测 → 领域专家并行抽词 → 联网核实 → 仲裁定稿 → 带上下文翻译），
质量最高但更慢更贵，适合正式发布的内容；</li>
<li><b>文本AI翻译</b>——粘贴文字直接翻，带风格与术语；</li>
<li><b>文档AI翻译</b>——上传 docx/pdf/xlsx/pptx/srt 等 12 种格式，保留结构翻译，支持原文/译文对照页。</li>
</ul>`
      },
      {
        id: 'pick',
        h: '怎么选？',
        html: `<p>日常内容用「音频/视频翻译」；专有名词多且要求前后一致用「术语库」；
要发布、要最高质量用「全能AI翻译」；纯文字用「文本」或「文档」。</p>`
      }
    ]
  },

  // ==================== 账户与设置 ====================
  {
    id: 'account',
    cat: 'account',
    title: '账号与安全',
    minutes: 4,
    updated: '2026-08-13',
    sections: [
      {
        id: 'profile',
        h: '头像与用户名',
        html: `<p>「<b>设置 → 账号与安全</b>」里可以更换头像（自动裁剪成正方形小图，只存在本机）、
修改用户名（需输入登录密码验证，改完自动换发新登录凭证）。</p>`
      },
      {
        id: 'password',
        h: '修改密码',
        html: `<p>同一页填原密码 + 新密码（至少 6 位）即可。忘记密码的话，请让管理员在「管理员后台」帮你处理，
或删除软件目录下 <code>data\\</code> 里的数据库重新初始化（会丢失所有数据，慎用）。</p>`
      },
      {
        id: 'delete',
        h: '注销账号',
        html: `<p>「账号与安全 → 危险操作」里可以永久注销当前账号：输入密码并勾选确认后，账号立即停用且无法再登录，
保存的 API 密钥会被清除。翻译任务与媒体文件保留在本机 data 目录，可由管理员清理。
<b>最后一个管理员不能注销</b>——先在管理员后台把别人设为管理员。</p>`
      },
      {
        id: 'admin',
        h: '管理员',
        html: `<p>第一个注册的账号是管理员，可在「管理员后台」查看所有用户与任务、启停账号、调整角色、查看用户反馈。</p>`
      }
    ]
  },
  {
    id: 'api-config',
    cat: 'account',
    title: 'API 配置指南',
    minutes: 8,
    updated: '2026-08-13',
    sections: [
      {
        id: 'overview',
        h: '配置入口',
        html: `<p>所有 AI 服务都在「<b>设置 → API 配置</b>」里配置，左侧分三类：
<b>大语言模型</b>（翻译、术语提取）、<b>语音识别 ASR</b>（音频转文字）、<b>语音合成 TTS</b>（AI 配音）。
密钥只保存在你本机的数据库里，不会上传到任何服务器。</p>`
      },
      {
        id: 'llm',
        h: '大语言模型（翻译模型）',
        html: `<p>服务商下拉支持 <b>DeepSeek、Qwen、Gemini、GPT、GLM、Claude、Grok</b> 等大模型，
以及 <b>谷歌翻译、微软翻译、DeepL</b> 三家传统机器翻译，还有两种自定义通用格式（OpenAI 格式 / Claude 格式）
可接任何兼容端点（中转站、one-api、ollama…）。</p>
<ol>
<li>选服务商 → Base URL 和推荐模型会自动填好；</li>
<li>粘贴 API Key（表单下方有各家控制台入口）；</li>
<li>点「拉取模型」从端点获取模型列表并选择；</li>
<li>点「测试连接」会真实翻译一句话验证配置；</li>
<li>「保存服务」入列。可以保存多家服务，用「设为默认」随时切换——<b>默认服务就是所有翻译模式实际用的模型</b>。</li>
</ol>
<p><b>Base URL 自动补全</b>：OpenAI/Claude 格式的地址保存时会自动补上 <code>/v1</code>
（已带 /v4、/v1beta 等版本号的地址不动），保存后按提示确认即可。</p>
<p><b>注意</b>：谷歌翻译/微软翻译/DeepL 是传统机翻——速度快，但不支持翻译风格和术语提示，
「全能AI翻译」「智能客服」等需要大模型的功能也用不了它们。</p>`
      },
      {
        id: 'asr',
        h: '语音识别（ASR）',
        html: `<p>两条路：<b>本地 Whisper</b>（内置离线，零配置免费，模型大小在提交任务时选）和
<b>Groq 云端</b>（whisper-large-v3 加速，有免费额度，注册与调用都需要魔法）。
到 <code>console.groq.com/keys</code> 创建 gsk_ 开头的 Key 填入即可。用哪个引擎是提交任务时选的，每个任务可以不同。</p>`
      },
      {
        id: 'tts',
        h: '语音合成（TTS）',
        html: `<p>三选一：<b>Edge-TTS</b>（微软语音，免费零配置，合成时需联网）、
<b>硅基流动 CosyVoice2</b>（只需 API Key）、<b>OpenAI 兼容</b>（自定义端点，Base/Key/模型全填）。
配好后在任务详情的「AI 配音」里选音色合成。</p>`
      },
    ]
  },
  {
    id: 'model-manage',
    cat: 'account',
    title: '模型管理与切换',
    minutes: 4,
    updated: '2026-08-13',
    sections: [
      {
        id: 'list',
        h: '已配置的模型服务',
        html: `<p>「大语言模型」面板下方的<b>已配置的模型服务</b>列表就是你的服务池：
每行可以启用/停用（开关）、设为默认（⋯ 菜单）、编辑、删除。带「默认」标记且启用的服务是全局翻译当前使用的。
删除默认服务时会自动把最近的启用服务顶上。</p>`
      },
      {
        id: 'manage-tab',
        h: '「模型管理」总览页',
        html: `<p>「<b>设置 → 模型管理</b>」汇总了所有模型配置：大语言模型服务列表、全能AI翻译的四个角色
（翻译模型/主 Agent/子 Agent/搜索引擎）、语音识别、语音合成、术语模型的配置状态，
每行都有「去配置」直达对应设置页。</p>`
      },
      {
        id: 'timeout',
        h: '超时设置',
        html: `<p>每个模型服务可以单独设请求超时（5-600 秒，建议 30-120）。模型响应慢、经常超时就调大；
想快速失败重试就调小。</p>`
      }
    ]
  },
  {
    id: 'privacy',
    cat: 'account',
    title: '隐私与数据',
    minutes: 3,
    updated: '2026-08-13',
    sections: [
      {
        id: 'where',
        h: '数据在哪',
        html: `<p>全部在软件目录的 <code>data\\</code> 里：数据库（账号、密钥、任务记录）+ 媒体文件 + 字幕。
拷走这个文件夹即完成备份/迁移。服务只监听本机 localhost，外网访问不到。</p>`
      },
      {
        id: 'send',
        h: '什么会被发送到外部',
        html: `<p>只有需要 AI 处理的内容会发给你配置的服务商：转写文本发给翻译模型、音频发给云端识别（选 Groq 时）、
译文发给 TTS 服务（选云端引擎时）。选本地 Whisper + 不开配音时，媒体内容不出本机。
LangSmith 调试功能会上报完整提示词，不调试就别填。</p>`
      },
      {
        id: 'safe',
        h: '安全建议',
        html: `<p>不要把 <code>data\\</code> 目录发给别人（里面有你的 API 密钥）；
不用的账号及时注销；密钥泄露就去服务商控制台重置。</p>`
      }
    ]
  },

  // ==================== 核心功能 ====================
  {
    id: 'video',
    cat: 'core',
    title: '音频/视频翻译教程',
    minutes: 15,
    updated: '2026-08-13',
    sections: [
      {
        id: 'upload',
        h: '1. 上传文件',
        html: `<p>支持常见视频（mp4/mkv/mov/avi…）与音频（mp3/wav/m4a/flac…）。
大文件会自动分块识别；Groq 免费层一次处理约 2 小时以内的内容。</p>`
      },
      {
        id: 'options',
        h: '2. 选择翻译设置',
        html: `<p>目标语言按分组选（常用/欧洲/中东/东南亚…）；识别引擎选 Groq（快，需 Key）或本地 Whisper
（离线，按机器性能选 small/medium/large-v3）；可以套用术语库和翻译风格。</p>`
      },
      {
        id: 'run',
        h: '3. 翻译与进度',
        html: `<p>提交后可在任务卡片看进度：提取音频 → 语音识别 → AI 翻译 → 完成。
关掉浏览器任务照跑；重启软件后未完成的任务会自动恢复。</p>`
      },
      {
        id: 'result',
        h: '4. 查看与导出',
        html: `<p>完成后可以：下载 <b>SRT 字幕</b>（原文/译文/双语）、下载<b>译文 TXT</b>、
进<b>字幕编辑器</b>逐行校对改时间轴、<b>字幕烧录</b>把字幕压进视频、<b>AI 配音</b>合成译文音轨。</p>`
      },
      {
        id: 'retry',
        h: '5. 不满意就重试',
        html: `<p>任务卡片的「重试」可以换识别引擎、换目标语言、换模型重跑，不用重新上传文件。
译文里如果有行没翻译成功，任务会明确标出来（不会静默混在里面）。</p>`
      }
    ]
  },
  {
    id: 'text',
    cat: 'core',
    title: '文本AI翻译',
    minutes: 3,
    updated: '2026-08-13',
    sections: [
      {
        id: 'use',
        h: '怎么用',
        html: `<p>「文本AI翻译」页粘贴文字 → 选目标语言（可选风格、术语库）→ 翻译。
结果可复制、下载对照 TXT。历史记录自动保存，随时回看。</p>`
      }
    ]
  },
  {
    id: 'doc',
    cat: 'core',
    title: '文档AI翻译',
    minutes: 6,
    updated: '2026-08-13',
    sections: [
      {
        id: 'formats',
        h: '支持的格式',
        html: `<p>docx、pdf、pptx、xlsx、srt、ass、md、txt、csv、html、json、xml 共 12 种。
解析引擎会保留文档结构。<b>PDF 走版式保持翻译</b>：先做版面分析识别标题/正文/图表/公式，
再按原栏宽与字号把译文重新流排回原位，图片、公式、页码保持不变，不是把译文盖在原文上。</p>`
      },
      {
        id: 'compare',
        h: '对照预览',
        html: `<p>翻译完成后点「对照」进入原文/译文分栏对照页，逐段核对；确认无误后下载译文文档。
PDF 与 HTML 还提供「版式对照」，左右并排渲染真实排版效果。</p>`
      }
    ]
  },
  {
    id: 'glossary',
    cat: 'core',
    title: '术语库管理',
    minutes: 8,
    updated: '2026-08-13',
    sections: [
      {
        id: 'concept',
        h: '术语库是什么',
        html: `<p>「原文术语 → 指定译法」的对照表，按项目组织。翻译时勾选术语库，
出现的术语会被强制按表翻译——保证人名、招式、产品名前后一致。</p>`
      },
      {
        id: 'manage',
        h: '管理入口',
        html: `<p>侧边栏「术语库」页可以建项目、增删改术语、导入导出。
全能AI翻译抽出的新词会自动入库：有佐证的直接启用，暂无佐证的存为<b>备选</b>（不生效），
你在术语库页面勾一下即可启用。</p>`
      },
      {
        id: 'apply',
        h: '在翻译中使用',
        html: `<p>音视频/文本/文档翻译的表单里都有「术语库」选择器，可多选。
同名术语以你勾选的库为准（优先级高于 AI 自动产出）。</p>`
      }
    ]
  },
  {
    id: 'subtitle',
    cat: 'core',
    title: '字幕编辑与烧录',
    minutes: 6,
    updated: '2026-08-13',
    sections: [
      {
        id: 'editor',
        h: '字幕编辑器',
        html: `<p>任务完成后进「字幕编辑器」：左边视频预览、右边逐行字幕（原文+译文），
可以改文字、改时间轴、增删行，边播边校对，改完保存导出。</p>`
      },
      {
        id: 'burn',
        h: '字幕烧录',
        html: `<p>「字幕样式/烧录」里选字体、字号、颜色、描边、位置，把字幕永久压进视频画面，
生成带字幕的新视频文件（原文件不动）。</p>`
      },
      {
        id: 'tts',
        h: 'AI 配音（TTS）',
        html: `<p>配好 TTS 引擎后，任务详情里点「AI 配音」选音色，把译文合成为配音音轨，
可与原视频混流导出。音色列表随所选引擎变化。</p>`
      }
    ]
  },

  // ==================== 进阶使用 ====================
  {
    id: 'agent',
    cat: 'advanced',
    title: '全能AI翻译（Agent 模式）',
    minutes: 10,
    updated: '2026-08-13',
    sections: [
      {
        id: 'how',
        h: '它是怎么工作的',
        html: `<p>一条多智能体流水线：<b>主 Agent</b> 读稿推测领域场景 → 派出多个<b>领域专家子 Agent</b>
并行抽取术语 → 拿不准的专名<b>联网核实</b>通行译法 → 主 Agent 仲裁定稿术语表 →
<b>翻译模型</b>带滚动上下文逐组翻译。运行现场页能实时看到每一步。</p>`
      },
      {
        id: 'config',
        h: '模型怎么配',
        html: `<p>「<b>设置 → API 配置 → 大语言模型 → 全能AI翻译</b>」：</p>
<ul>
<li><b>翻译模型</b>：负责最终逐行翻译，留空=用默认的大语言模型服务；</li>
<li>高级设置里可分开配<b>主 Agent</b>（要求理解力好，调用少）、<b>子 Agent</b>（调用量大，选便宜快的）、
<b>搜索引擎</b>（Tavily/博查/Serper/搜索模型，不配也能用）；</li>
<li>全部留空也能跑——统一用默认服务。</li>
</ul>`
      },
      {
        id: 'profiles',
        h: '领域专家档案',
        html: `<p>内置通用/IT/医疗/法律/游戏五个专家档案（判定标准、翻译惯例、搜索提示词）。
「全能AI翻译」页里可以新建自己的专家档案，也能采纳系统基于真实搜索结果提出的档案修正提议。</p>`
      },
      {
        id: 'langsmith',
        h: 'LangSmith 调试（开发者）',
        html: `<p>填了 LangSmith Key 后，每次模型调用的完整提示词与返回会上报到 LangSmith 网页端便于排查。
<b>内容含完整台词，会发送到 LangSmith 服务器</b>——不调试就留空。</p>`
      }
    ]
  },
  {
    id: 'selection',
    cat: 'advanced',
    title: '划词翻译（浏览器扩展）',
    minutes: 6,
    updated: '2026-08-15',
    sections: [
      {
        id: 'install',
        h: '安装与配对',
        html: `<ol>
<li>侧边栏「划词翻译」页点<b>下载扩展</b>，解压得到 huyi-extension 文件夹；</li>
<li>浏览器打开扩展管理页（chrome://extensions），开「开发者模式」，「加载已解压的扩展程序」选这个文件夹；</li>
<li>回到「划词翻译」页点<b>配对</b>——扩展拿到 90 天有效的本机凭证，之后翻译都走你本机的狐译。</li>
</ol>
<p>狐译客户端关掉再打开后，扩展会自动重连（后台每半分钟探一次，也可在「狐」图标弹窗点「重新连接」立刻重试），
<b>已经打开的网页不需要刷新</b>就能接着用。</p>`
      },
      {
        id: 'use',
        h: '五种用法',
        html: `<ul>
<li><b>划词</b>：网页上选中文字 → 右键「狐译（划词翻译）」或点悬浮球；译文展示方式在「狐」图标弹窗里三选一：<b>替换原文 / 放在原文下方 / 弹窗展示</b>；</li>
<li><b>输入框</b>：在输入框、文本框或富文本编辑器里打完字，<b>连按三下空格</b>就地翻译；</li>
<li><b>图片</b>：图片上右键「狐译（翻译这张图片）」，译文原位回填到图上；结果浮层默认在图片旁边，按住标题栏可拖动，位置会被记住；</li>
<li><b>整页</b>：右键「狐译（翻译整个网页）」或 Alt+P，逐段替换页面文字。已翻过的内容会被标记跳过，动态加载出来的新内容自动追译；</li>
<li><b>原文/译文切换</b>：Alt+R 在原文与译文之间来回切，译文缓存在本地，切换不再调用大模型。</li>
</ul>
<p>目标语言与模型跟随你在狐译网页版的设置。</p>`
      },
      {
        id: 'history',
        h: '独立的翻译历史',
        html: `<p>划词、整页、图片、输入框翻译的记录都进「划词翻译」页下方的<b>翻译历史</b>，
可以查看逐行对照、导出译文/对照、单条删除或一键清空。这些记录<b>不会</b>出现在「文本 AI 翻译」的历史里，
两边彻底分开；「翻译历史」页仍然汇总全部记录，可按「插件」筛选。</p>`
      }
    ]
  },
  {
    id: 'styles',
    cat: 'advanced',
    title: '翻译风格',
    minutes: 4,
    updated: '2026-08-13',
    sections: [
      {
        id: 'preset',
        h: '内置与自定义风格',
        html: `<p>内置古风文雅/正式书面/口语自然/网络流行/幽默轻松五种风格；
「<b>设置 → 翻译偏好</b>」里可以保存自己的风格预设（比如「影视字幕腔」），出现在所有翻译模式的风格下拉里。</p>`
      },
      {
        id: 'default',
        h: '默认风格',
        html: `<p>「翻译偏好 → 默认风格」保存后，各翻译模式默认带上它，任务里可临时修改或关闭；
清空并保存即取消默认。注意：传统机翻服务商（谷歌/微软/DeepL）不支持风格。</p>`
      }
    ]
  },
  {
    id: 'history',
    cat: 'advanced',
    title: '翻译历史',
    minutes: 2,
    updated: '2026-08-13',
    sections: [
      {
        id: 'where',
        h: '在哪看',
        html: `<p>侧边栏「翻译历史」汇总所有模式的任务：筛选、重下载、删除。
文本/文档翻译的历史也在各自页面里可回看。</p>`
      }
    ]
  },

  // ==================== 常见问题 ====================
  {
    id: 'faq',
    cat: 'faq',
    title: '常见问题（FAQ）',
    minutes: 6,
    updated: '2026-08-13',
    sections: [
      {
        id: 'start-fail',
        h: '启动/访问问题',
        html: `<ul>
<li><b>双击 start.bat 被 Windows 拦截</b>：SmartScreen 点「更多信息 → 仍要运行」；杀毒误报加白名单；</li>
<li><b>端口被占用</b>：用 <code>start.bat 9090</code> 换端口启动，浏览器访问 <code>localhost:9090</code>；</li>
<li><b>页面打不开</b>：等 10 秒再刷新（首次启动要初始化数据库）。</li>
</ul>`
      },
      {
        id: 'key-fail',
        h: 'API 配置问题',
        html: `<ul>
<li><b>测试连接失败</b>：检查 Key 是否复制完整、账户是否有余额；Groq/Gemini/GPT/Claude 国内要开魔法；</li>
<li><b>提示未配置翻译模型</b>：去「设置 → API 配置 → 大语言模型」保存一条服务并确认带「默认」标记；</li>
<li><b>Base URL 填错</b>：保存时会自动补 /v1；智谱是 /v4、Gemini 官方兼容端点是 /v1beta/openai，选服务商预填的地址一般不用改；</li>
<li><b>拉取模型失败</b>：传统机翻（谷歌/微软/DeepL）没有模型列表，直接保存即可。</li>
</ul>`
      },
      {
        id: 'result-fail',
        h: '翻译结果问题',
        html: `<ul>
<li><b>有几行没翻译</b>：任务会明确标出未译行数；点「重试」重跑，或换个模型；</li>
<li><b>术语前后不一致</b>：改用「全能AI翻译」，并在「新词存入」里为该系列固定一个术语库；</li>
<li><b>翻译太慢</b>：换不带思考(reasoning)的模型；本地识别换小一号的 Whisper 模型；</li>
<li><b>识别乱码/幻觉</b>：换 large-v3 模型或 Groq；纯音乐片段出现幻觉句子会被自动过滤。</li>
</ul>`
      },
      {
        id: 'data-q',
        h: '数据问题',
        html: `<ul>
<li><b>数据在哪/怎么搬家</b>：全在软件目录 <code>data\\</code> 里，拷走即迁移；</li>
<li><b>安全吗</b>：服务只监听本机，密钥存本机数据库；不要把 data 目录发给别人。</li>
</ul>`
      }
    ]
  },

  // ==================== 更新日志 ====================
  {
    id: 'changelog',
    cat: 'changelog',
    title: '更新日志',
    minutes: 3,
    updated: '2026-08-15',
    sections: [
      {
        id: 'v22',
        h: 'V2.2（2026-08-15）划词翻译扩展升级 v1.3',
        html: `<ul>
<li><b>划词翻译历史独立</b>：划词/整页/图片/输入框的记录进「划词翻译」页自己的历史，不再混进「文本 AI 翻译」历史；</li>
<li><b>连接自动恢复</b>：狐译客户端重启后扩展自动重连（后台定时探活 + 恢复广播），已打开的网页无需刷新；</li>
<li><b>图片翻译浮层可拖动</b>：默认摆在图片旁边不再挡住原图，拖过的位置会被记住；</li>
<li><b>整页翻译增量化</b>：已翻内容打标跳过，动态加载出来的新内容自动追译，不重复烧大模型；</li>
<li><b>原文/译文本地切换</b>：译文缓存在本地，Alt+R 来回切换零调用（不再是「恢复原文就丢译文」）；</li>
<li><b>划词展示方式三选一</b>：替换原文 / 放在原文下方 / 弹窗展示；</li>
<li><b>输入框翻译</b>：任意输入框里连按三下空格，把输入内容就地翻成目标语言。</li>
</ul>`
      },
      {
        id: 'v21',
        h: 'V2.1（2026-08-13）设置中心改版 · 品牌更名「狐译」',
        html: `<ul>
<li>品牌更名：灵译 → <b>狐译</b>；</li>
<li>全新设置中心：API 配置按服务类型分区（大语言模型/语音识别/语音合成）；</li>
<li>大语言模型支持多服务商（DeepSeek/Qwen/Gemini/GPT/GLM/Claude/Grok/谷歌翻译/微软翻译/DeepL/自定义），
可保存多个服务并一键切换默认；新增 Claude 原生协议与三家传统机翻直连；</li>
<li>Base URL 自动补全 /v1、真实「测试连接」、每服务独立超时；</li>
<li>全能AI翻译独立配置页：翻译模型 + 高级设置（主/子 Agent、搜索引擎）；</li>
<li>账号与安全：头像上传、注销账号；</li>
<li>全新帮助中心（本页面）+「反馈与建议」通道。</li>
</ul>`
      },
      {
        id: 'v20',
        h: 'V2.0（2026-08）多智能体质量增强系统 + 便携分发版',
        html: `<ul>
<li>全能AI翻译（Agent 模式）：场景推测 → 领域专家并行抽词 → 联网核实 → 置信度仲裁 → 滚动上下文翻译；</li>
<li>运行现场页：实时展示每一步的输入输出与耗时；LangSmith 云端调试；自建领域专家档案；</li>
<li>划词翻译浏览器扩展（划词/图片/整页三种模式，90 天配对凭证）；</li>
<li>文档AI翻译扩展到 12 种格式（新增 xlsx/pptx），PDF 原位回填、对照预览页；</li>
<li>智能客服：基于使用手册的 RAG 问答悬浮球；</li>
<li>便携分发版：内置 Java/Python/FFmpeg，解压即用。</li>
</ul>`
      },
      {
        id: 'v10',
        h: 'V1.0 首个正式版',
        html: `<ul>
<li>音频/视频翻译、术语库AI视频翻译、文本AI翻译三大模式（术语库模式已于 2026-08 并入全能AI翻译）；</li>
<li>本地 Whisper 与 Groq 双识别引擎、字幕编辑器、字幕烧录、AI 配音（Edge/硅基流动/OpenAI 兼容）；</li>
<li>术语库管理、翻译风格预设、翻译历史、管理员后台。</li>
</ul>`
      }
    ]
  }
]

/** 按 id 找文档 */
export function docById (id) {
  return HELP_DOCS.find(d => d.id === id) || null
}

/** 分类下的文档列表 */
export function docsByCat (catId) {
  return HELP_DOCS.filter(d => d.cat === catId)
}

/** 全文检索（标题 + 章节标题 + 内容纯文本），返回 [{doc, section, snippet}] */
export function searchDocs (keyword) {
  const q = (keyword || '').trim().toLowerCase()
  if (!q) return []
  const out = []
  for (const d of HELP_DOCS) {
    for (const s of d.sections) {
      const plain = s.html.replace(/<[^>]+>/g, '')
      const hay = (d.title + ' ' + s.h + ' ' + plain).toLowerCase()
      const idx = hay.indexOf(q)
      if (idx >= 0) {
        const from = Math.max(0, idx - 20)
        out.push({
          doc: d, section: s,
          snippet: (from > 0 ? '…' : '') + hay.substr(from, 80).replace(/\n/g, ' ') + '…'
        })
      }
    }
  }
  return out.slice(0, 30)
}
