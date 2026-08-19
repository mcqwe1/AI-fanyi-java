<template>
  <div class="page">
    <div class="page-head head-row">
      <div>
        <h2>设置</h2>
        <p>配置您的 API、模型与偏好设置</p>
      </div>
      <el-button type="text" icon="el-icon-question" @click="openHelp()">帮助中心</el-button>
    </div>

    <div class="body">
      <el-tabs v-model="tab">
        <!-- ==================== API 配置 ==================== -->
        <el-tab-pane label="API 配置" name="api">
          <div class="api-banner">
            <span><i class="el-icon-umbrella" />
              在这里配置各类 AI 服务接口，狐译支持多种主流大模型与语音服务，您可以随时添加或切换。</span>
            <el-link type="primary" :underline="false" @click="openHelp('api-config')">
              查看配置指南 <i class="el-icon-top-right" /></el-link>
          </div>

          <div class="api-layout">
            <!-- 左侧服务类型 -->
            <div class="svc-side">
              <div class="svc-side-title">服务类型</div>
              <div v-for="s in svcTypes" :key="s.id" class="svc-item"
                   :class="{ active: svc === s.id }" @click="svc = s.id">
                <i :class="s.icon" />
                <span class="svc-meta">
                  <span class="svc-name">{{ s.name }}</span>
                  <span class="svc-desc">{{ s.desc }}</span>
                </span>
              </div>
            </div>

            <!-- 右侧面板 -->
            <div class="svc-panel">
              <!-- ========== 大语言模型 ========== -->
              <template v-if="svc === 'llm'">
                <el-tabs v-model="llmSub" class="sub-tabs">
                  <!-- 翻译模型（多服务商） -->
                  <el-tab-pane label="翻译模型" name="main">
                    <div class="panel-head">
                      <span class="panel-title">大语言模型配置
                        <el-tag size="mini" type="primary" effect="plain">已配置 {{ services.length }}</el-tag>
                      </span>
                      <span class="panel-actions">
                        <el-tag v-if="defaultService" size="mini" type="success" effect="plain">
                          <i class="el-icon-success" /> 当前使用 {{ defaultService.providerName }}</el-tag>
                        <el-button size="small" icon="el-icon-connection" :loading="testingSvc"
                                   @click="testSvc">测试连接</el-button>
                      </span>
                    </div>
                    <div v-if="testResult" class="test-result" :class="{ ok: testOk }">{{ testResult }}</div>

                    <el-divider content-position="left">基础配置</el-divider>
                    <el-form label-width="120px" class="svc-form">
                      <el-form-item label="服务商">
                        <el-select v-model="svcForm.provider" style="width: 320px" @change="onProviderChange">
                          <el-option-group label="主流服务商">
                            <el-option v-for="p in providers.filter(x => !x.custom)" :key="p.id"
                                       :label="p.name" :value="p.id" />
                          </el-option-group>
                          <el-option-group label="自定义">
                            <el-option v-for="p in providers.filter(x => x.custom)" :key="p.id"
                                       :label="p.name" :value="p.id" />
                          </el-option-group>
                        </el-select>
                        <span v-if="currentProvider && currentProvider.docsUrl" style="margin-left:10px">
                          <el-link type="info" :href="currentProvider.docsUrl" target="_blank" :underline="false">
                            <i class="el-icon-link" /> 控制台</el-link>
                        </span>
                        <div v-if="currentProvider && currentProvider.note" class="tip">{{ currentProvider.note }}</div>
                      </el-form-item>

                      <el-form-item label="Base URL">
                        <el-input v-model="svcForm.baseUrl"
                                  :placeholder="currentProvider && currentProvider.defaultBaseUrl
                                    ? currentProvider.defaultBaseUrl : 'https://your-endpoint.com/v1'" />
                        <div class="tip">
                          <template v-if="isLlmProto">保存时会自动补全 /v1（地址里已有 /v4、/v1beta 等版本号则不改动），保存后以提示为准。</template>
                          <template v-else>该服务商使用固定端点，一般无需修改。</template>
                        </div>
                      </el-form-item>

                      <el-form-item label="API Key">
                        <el-input v-model="svcForm.apiKey" show-password
                                  :placeholder="editingSvc && editingSvc.apiKey && editingSvc.apiKey.set
                                    ? `已配置 ${editingSvc.apiKey.masked}，留空不改` : '粘贴你的 API Key'" />
                        <div class="tip">{{ currentProvider ? currentProvider.keyHint : '' }}；密钥仅保存在你本机的数据库中</div>
                      </el-form-item>

                      <el-form-item v-if="currentProvider && currentProvider.needModel"
                                    :label="currentProvider.modelLabel">
                        <div style="display:flex; gap:8px">
                          <el-select v-model="svcForm.model" filterable allow-create default-first-option
                                     :placeholder="currentProvider.defaultModel
                                       ? `如 ${currentProvider.defaultModel}` : '选择或输入'" style="flex:1">
                            <el-option v-for="m in svcModels" :key="m" :label="m" :value="m" />
                          </el-select>
                          <el-button v-if="currentProvider.canListModels" :loading="fetchingSvcModels"
                                     @click="fetchSvcModels">拉取模型</el-button>
                        </div>
                        <div class="tip" v-if="svcForm.provider === 'ms-mt'">多服务/区域资源必填（如 eastasia），全球资源留空</div>
                        <div class="tip" v-else>建议翻译用不带思考(reasoning)的模型，避免翻译时间过长</div>
                      </el-form-item>

                      <el-form-item label="超时设置（秒）">
                        <el-input-number v-model="svcForm.timeoutSec" :min="5" :max="600" :step="10" />
                        <div class="tip">单次请求超时时间，建议 30-120 秒</div>
                      </el-form-item>

                      <el-form-item label="并发请求数">
                        <el-input-number v-model="svcForm.concurrency" :min="0" :max="32" :step="4" />
                        <div class="tip">
                          同时向该端点发几路翻译请求。0 = 用默认值（8）。长视频与整本文档才吃得到并发：
                          实测 1778 行需要 45 批，8 路要 28 秒、16 路约 15 秒；短内容一波就跑完，调了也没用。
                          调太高会触发服务商限流，被限流的批次会保留原文。
                        </div>
                      </el-form-item>

                      <el-form-item>
                        <el-button type="primary" :loading="savingSvc" @click="saveSvc(false)">
                          {{ svcForm.id ? '保存修改' : '保存服务' }}</el-button>
                        <el-button :loading="savingSvc" @click="saveSvc(true)">保存并设为默认</el-button>
                        <el-button v-if="svcForm.id" type="text" @click="resetSvcForm">改为新增</el-button>
                      </el-form-item>
                    </el-form>

                    <!-- 已配置的模型服务 -->
                    <div class="svc-list-head">
                      <span class="panel-title">已配置的模型服务</span>
                      <el-button size="small" type="primary" plain icon="el-icon-plus"
                                 @click="resetSvcForm">添加服务</el-button>
                    </div>
                    <div v-if="!services.length" class="empty-tip">
                      还没有配置任何模型服务——在上方选择服务商、填好 Key，点「保存服务」。
                    </div>
                    <div v-for="row in services" :key="row.id" class="svc-row"
                         :class="{ off: !row.enabled }">
                      <span class="svc-logo">{{ row.providerName.slice(0, 1) }}</span>
                      <span class="svc-row-name">{{ row.providerName }}</span>
                      <el-tag v-if="row.model" size="mini" effect="plain" class="svc-model">{{ row.model }}</el-tag>
                      <el-tag v-if="row.isDefault" size="mini" type="success">默认</el-tag>
                      <span class="svc-row-time">更新时间：{{ fmtTime(row.updatedAt) }}</span>
                      <el-switch :value="row.enabled" @change="v => toggleSvc(row, v)" />
                      <el-dropdown trigger="click" @command="cmd => svcRowCmd(cmd, row)">
                        <span class="svc-more"><i class="el-icon-more" /></span>
                        <el-dropdown-menu slot="dropdown">
                          <el-dropdown-item command="default" :disabled="row.isDefault"
                                            icon="el-icon-star-off">设为默认</el-dropdown-item>
                          <el-dropdown-item command="edit" icon="el-icon-edit">编辑</el-dropdown-item>
                          <el-dropdown-item command="del" icon="el-icon-delete" divided>删除</el-dropdown-item>
                        </el-dropdown-menu>
                      </el-dropdown>
                    </div>
                    <div class="tip" style="margin-top:8px" v-if="services.length">
                      「默认」的服务是所有翻译模式实际使用的模型；传统机翻（谷歌/微软/DeepL）不支持风格与术语提示。
                    </div>
                  </el-tab-pane>

                  <!-- 全能AI翻译 -->
                  <el-tab-pane label="全能AI翻译" name="agent">
                    <div class="tip" style="margin-bottom:12px">
                      这一页只影响「全能AI翻译」模式，<b>全部可以留空</b>——留空就沿用「翻译模型」里的默认服务。
                    </div>

                    <el-divider content-position="left">翻译模型（负责最终逐行翻译）</el-divider>
                    <el-form label-width="120px" class="svc-form">
                      <el-form-item label="Base URL">
                        <el-input v-model="form.agentTranslateBaseUrl" placeholder="留空=用默认的大语言模型服务" />
                      </el-form-item>
                      <el-form-item label="API Key">
                        <el-input v-model="form.agentTranslateApiKey" :placeholder="ph('agentTranslateApiKey')" show-password />
                      </el-form-item>
                      <el-form-item label="模型">
                        <div style="display:flex; gap:8px">
                          <el-select v-model="form.agentTranslateModel" filterable allow-create default-first-option
                                     placeholder="选择或输入模型名，如 deepseek-chat" style="flex:1">
                            <el-option v-for="m in agentTranslateModels" :key="m" :label="m" :value="m" />
                          </el-select>
                          <el-button :loading="fetchingAgentTranslate" @click="fetchAgentTranslateModels">拉取模型</el-button>
                        </div>
                      </el-form-item>
                    </el-form>

                    <el-collapse class="adv-collapse">
                      <el-collapse-item name="adv">
                        <template slot="title">
                          <i class="el-icon-setting" style="margin-right:6px" /> 高级设置（搜索引擎 · 主 Agent · 子 Agent · LangSmith）
                        </template>

                        <el-form label-width="120px" class="svc-form">
                          <el-divider content-position="left">主 Agent（读稿与仲裁）</el-divider>
                          <div class="tip" style="margin-bottom:8px">
                            负责判断内容属于哪个领域、以及最后汇总各专家的意见。要求理解力好，调用次数少。
                          </div>
                          <el-form-item label="Base URL">
                            <el-input v-model="form.agentMainBaseUrl" placeholder="留空=用默认的大语言模型服务" />
                          </el-form-item>
                          <el-form-item label="API Key">
                            <el-input v-model="form.agentMainApiKey" :placeholder="ph('agentMainApiKey')" show-password />
                          </el-form-item>
                          <el-form-item label="模型">
                            <div style="display:flex; gap:8px">
                              <el-select v-model="form.agentMainModel" filterable allow-create default-first-option
                                         placeholder="选择或输入模型名" style="flex:1">
                                <el-option v-for="m in agentMainModels" :key="m" :label="m" :value="m" />
                              </el-select>
                              <el-button :loading="fetchingAgentMain" @click="fetchAgentMainModels">拉取模型</el-button>
                            </div>
                          </el-form-item>

                          <el-divider content-position="left">子 Agent（领域专家）</el-divider>
                          <div class="tip" style="margin-bottom:8px">
                            负责抽取术语、核实译法。会被并行调用多次（每个领域一个专家），建议选便宜快速的模型。
                          </div>
                          <el-form-item label="Base URL">
                            <el-input v-model="form.agentSubBaseUrl" placeholder="留空=用默认的大语言模型服务" />
                          </el-form-item>
                          <el-form-item label="API Key">
                            <el-input v-model="form.agentSubApiKey" :placeholder="ph('agentSubApiKey')" show-password />
                          </el-form-item>
                          <el-form-item label="模型">
                            <div style="display:flex; gap:8px">
                              <el-select v-model="form.agentSubModel" filterable allow-create default-first-option
                                         placeholder="选择或输入模型名" style="flex:1">
                                <el-option v-for="m in agentSubModels" :key="m" :label="m" :value="m" />
                              </el-select>
                              <el-button :loading="fetchingAgentSub" @click="fetchAgentSubModels">拉取模型</el-button>
                            </div>
                          </el-form-item>

                          <el-divider content-position="left">联网核实（搜索引擎）</el-divider>
                          <div class="tip" style="margin-bottom:8px">
                            遇到拿不准的专有名词时上网查一下通行译法。<b>不配也能用</b>，只是生僻专名的准确率会下降。
                          </div>
                          <el-form-item label="搜索引擎">
                            <div class="engine-list">
                              <div v-for="e in searchEngines" :key="e.id" class="engine-card"
                                   :class="{ active: form.searchProvider === e.id }" @click="form.searchProvider = e.id">
                                <div class="engine-head">
                                  <span class="engine-name">{{ e.name }}</span>
                                  <el-tag size="mini" :type="e.tag === 'free' ? 'success' : (e.tag === 'cloud' ? '' : 'info')">
                                    {{ e.tag === 'free' ? '免费' : (e.tag === 'cloud' ? '需Key' : '自定义') }}
                                  </el-tag>
                                  <el-tag v-if="e.configured && e.needKey" size="mini" type="success" effect="plain">已就绪</el-tag>
                                </div>
                                <div class="engine-desc">{{ e.desc }}</div>
                              </div>
                            </div>
                          </el-form-item>
                          <template v-if="currentSearchEngine && currentSearchEngine.needKey">
                            <el-form-item label="搜索 API Key">
                              <el-input v-model="form.searchApiKey" :placeholder="ph('searchApiKey')" show-password />
                            </el-form-item>
                            <el-form-item label="搜索 Base URL">
                              <el-input v-model="form.searchBaseUrl"
                                        :placeholder="currentSearchEngine.defaultBaseUrl || '留空=用默认端点'" />
                            </el-form-item>
                          </template>

                          <el-divider content-position="left">LangSmith 云端调试（开发者功能）</el-divider>
                          <div class="tip" style="margin-bottom:8px">
                            把每次模型调用的<b>完整提示词和返回</b>上报到 LangSmith 网页端，方便逐步排查。
                            <b>注意：内容含视频完整台词，会发送到 LangSmith 的服务器</b>——不调试就别填，留空即完全关闭。
                          </div>
                          <el-form-item label="LangSmith Key">
                            <el-input v-model="form.langsmithApiKey" :placeholder="ph('langsmithApiKey')" show-password />
                          </el-form-item>
                          <el-form-item label="项目名">
                            <div style="display:flex; gap:8px">
                              <el-input v-model="form.langsmithProject" placeholder="留空=aifanyi" style="flex:1" />
                              <el-button :loading="testingLs" @click="testLangsmith">测试连接</el-button>
                            </div>
                            <div v-if="lsTestResult" class="tip">{{ lsTestResult }}</div>
                          </el-form-item>
                        </el-form>
                      </el-collapse-item>
                    </el-collapse>

                    <div style="margin-top:16px">
                      <el-button type="primary" :loading="savingAgent" @click="saveAgent">保存全能AI翻译配置</el-button>
                    </div>
                  </el-tab-pane>

                </el-tabs>
              </template>

              <!-- ========== 语音识别（ASR） ========== -->
              <template v-else-if="svc === 'asr'">
                <div class="panel-head">
                  <span class="panel-title">语音识别配置
                    <el-tag size="mini" type="primary" effect="plain">已配置 {{ asrConfiguredCount }}</el-tag>
                  </span>
                </div>

                <el-divider content-position="left">基础配置</el-divider>
                <el-form label-width="120px" class="svc-form">
                  <el-form-item label="服务商">
                    <el-select v-model="asrChoice" style="width: 320px">
                      <el-option v-for="e in asrEngines" :key="e.id" :label="e.name" :value="e.id" />
                    </el-select>
                    <div class="tip">{{ currentAsrEngine ? currentAsrEngine.desc : '' }}</div>
                  </el-form-item>

                  <template v-if="asrChoice === 'groq'">
                    <el-form-item label="API Key">
                      <el-input v-model="groqKeyInput" :placeholder="ph('groqApiKey')" show-password />
                      <div class="tip">
                        <el-link type="info" href="https://console.groq.com/keys" target="_blank" :underline="false">
                          console.groq.com/keys</el-link> 注册后创建（gsk_ 开头）；国内网络需要魔法
                      </div>
                    </el-form-item>
                    <el-form-item>
                      <el-button type="primary" :loading="savingGroq" @click="saveGroqKey">保存 Groq 密钥</el-button>
                    </el-form-item>
                  </template>
                  <template v-else>
                    <el-form-item label=" ">
                      <div class="local-asr-card">
                        <i class="el-icon-cpu" />
                        <div>
                          <b>内置离线识别，无需任何配置。</b>
                          <div class="tip">模型大小（small/medium/large-v3）在提交任务时选择；首次使用自动加载，全程本机运行、不需要联网。</div>
                        </div>
                      </div>
                    </el-form-item>
                  </template>
                </el-form>

                <div class="svc-list-head">
                  <span class="panel-title">已配置的识别服务</span>
                </div>
                <div v-for="e in asrEngines" :key="e.id" class="svc-row">
                  <span class="svc-logo">{{ e.name.slice(0, 1) }}</span>
                  <span class="svc-row-name">{{ e.name }}</span>
                  <el-tag v-if="!e.needKey" size="mini" type="success">内置 · 恒可用</el-tag>
                  <el-tag v-else-if="e.configured" size="mini" type="success">已配置</el-tag>
                  <el-tag v-else size="mini" type="info">未配置</el-tag>
                  <span class="svc-row-time">{{ e.desc }}</span>
                </div>
                <div class="tip" style="margin-top:8px">
                  用哪个识别引擎是在提交任务时选的（每个任务可以不同），这里只负责保存云端服务的密钥。
                </div>
              </template>

              <!-- ========== 语音合成（TTS） ========== -->
              <template v-else>
                <div class="panel-head">
                  <span class="panel-title">语音合成配置
                    <el-tag size="mini" type="primary" effect="plain">已配置 {{ ttsConfiguredCount }}</el-tag>
                  </span>
                  <el-tag v-if="currentTtsEngine" size="mini" type="success" effect="plain">
                    当前使用 {{ currentTtsEngine.name }}</el-tag>
                </div>

                <el-divider content-position="left">基础配置</el-divider>
                <el-form label-width="120px" class="svc-form">
                  <el-form-item label="配音引擎">
                    <el-select v-model="form.ttsProvider" style="width: 320px">
                      <el-option v-for="e in ttsEngines" :key="e.id" :label="e.name" :value="e.id" />
                    </el-select>
                    <div class="tip">{{ currentTtsSelected ? currentTtsSelected.desc : '请选择配音引擎' }}</div>
                  </el-form-item>

                  <template v-if="form.ttsProvider === 'siliconflow'">
                    <el-form-item label="API Key">
                      <el-input v-model="form.ttsApiKey" :placeholder="ph('ttsApiKey')" show-password />
                      <div class="tip">到 siliconflow.cn 注册 → API 密钥页新建（sk- 开头）</div>
                    </el-form-item>
                  </template>
                  <template v-else-if="form.ttsProvider === 'openai'">
                    <el-form-item label="Base URL">
                      <el-input v-model="form.ttsBaseUrl" placeholder="OpenAI 兼容端点，如 https://api.openai.com/v1" />
                    </el-form-item>
                    <el-form-item label="API Key">
                      <el-input v-model="form.ttsApiKey" :placeholder="ph('ttsApiKey')" show-password />
                    </el-form-item>
                    <el-form-item label="模型">
                      <div style="display:flex; gap:8px">
                        <el-select v-model="form.ttsModel" filterable allow-create default-first-option
                                   placeholder="选择或输入模型名，如 tts-1" style="flex:1">
                          <el-option v-for="m in ttsModels" :key="m" :label="m" :value="m" />
                        </el-select>
                        <el-button :loading="fetchingTtsModels" @click="fetchTtsModels">拉取模型</el-button>
                      </div>
                      <div class="tip">调用 {Base URL}/audio/speech 合成配音</div>
                    </el-form-item>
                  </template>
                  <el-form-item v-else-if="form.ttsProvider === 'edge'" label=" ">
                    <div class="local-asr-card">
                      <i class="el-icon-headset" />
                      <div>
                        <b>免费开箱即用，无需任何配置。</b>
                        <div class="tip">微软 Edge 语音，合成时需联网；音色在配音编辑器里选择。</div>
                      </div>
                    </div>
                  </el-form-item>

                  <el-form-item>
                    <el-button type="primary" :loading="savingTts" @click="saveTts">保存语音合成配置</el-button>
                  </el-form-item>
                </el-form>

                <div class="svc-list-head">
                  <span class="panel-title">已配置的配音服务</span>
                </div>
                <div v-for="e in ttsEngines" :key="e.id" class="svc-row">
                  <span class="svc-logo">{{ e.name.slice(0, 1) }}</span>
                  <span class="svc-row-name">{{ e.name }}</span>
                  <el-tag v-if="settings && settings.ttsProvider === e.id" size="mini" type="success">当前使用</el-tag>
                  <el-tag v-if="e.configured" size="mini" type="success" effect="plain">已就绪</el-tag>
                  <el-tag v-else size="mini" type="info">未配置</el-tag>
                  <span class="svc-row-time">{{ e.desc }}</span>
                </div>
              </template>
            </div>
          </div>
        </el-tab-pane>

        <!-- ==================== 模型管理 ==================== -->
        <el-tab-pane label="模型管理" name="models">
          <div class="tip" style="margin-bottom:14px">
            所有已配置模型的总览。翻译任务实际用哪个模型，取决于这里各处的「默认 / 当前使用」标记。
          </div>

          <el-divider content-position="left">大语言模型服务（全局翻译）</el-divider>
          <el-table :data="services" size="small" stripe empty-text="还没有配置模型服务，去「API 配置」添加">
            <el-table-column label="服务商" width="180">
              <template slot-scope="{ row }">
                {{ row.providerName }}
                <el-tag v-if="row.isDefault" size="mini" type="success" style="margin-left:4px">默认</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="model" label="模型/区域" show-overflow-tooltip />
            <el-table-column label="协议" width="100">
              <template slot-scope="{ row }">{{ protoName(row.protocol) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template slot-scope="{ row }">
                <el-tag size="mini" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="160">
              <template slot-scope="{ row }">{{ fmtTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="170">
              <template slot-scope="{ row }">
                <el-button type="text" :disabled="row.isDefault" @click="setDefaultSvc(row)">设为默认</el-button>
                <el-button type="text" @click="gotoEditSvc(row)">编辑</el-button>
                <el-button type="text" class="del" @click="deleteSvc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">全能AI翻译（Agent 模式）</el-divider>
          <el-table :data="agentModelRows" size="small" stripe>
            <el-table-column prop="name" label="角色" width="180" />
            <el-table-column prop="status" label="配置" show-overflow-tooltip />
            <el-table-column label="操作" width="100">
              <template>
                <el-button type="text" @click="goto('api', 'llm', 'agent')">去配置</el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-divider content-position="left">语音识别 / 语音合成</el-divider>
          <el-table :data="voiceModelRows" size="small" stripe>
            <el-table-column prop="name" label="服务" width="180" />
            <el-table-column prop="status" label="配置" show-overflow-tooltip />
            <el-table-column label="操作" width="100">
              <template slot-scope="{ row }">
                <el-button type="text" @click="goto('api', row.svc, row.sub)">去配置</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ==================== 翻译偏好 ==================== -->
        <el-tab-pane label="翻译偏好" name="style">
          <el-form label-width="150px" style="max-width:640px">
            <el-divider content-position="left">翻译风格（默认）</el-divider>
            <el-form-item label="默认风格">
              <div class="style-presets">
                <el-tag v-for="p in allPresets" :key="p.label" size="small" effect="plain"
                        class="style-tag" @click="form.stylePrompt = p.prompt">{{ p.label }}</el-tag>
              </div>
              <el-input v-model="form.stylePrompt" type="textarea" :rows="2" maxlength="500" show-word-limit
                        placeholder="如：古风文雅，用词考究…（留空=不设默认风格）" />
              <div class="tip">保存后作为各翻译模式的默认风格，任务里可临时修改或关闭；清空并保存即取消默认。
                想长期保存多个自定义风格，见下方「我的翻译风格预设」。</div>
              <div style="margin-top:8px">
                <el-button type="primary" size="small" :loading="savingStyle" @click="saveStylePrompt">保存默认风格</el-button>
              </div>
            </el-form-item>

            <el-divider content-position="left">我的翻译风格预设</el-divider>
            <el-form-item label="自定义风格">
              <div class="tip" style="margin-bottom:8px">
                这里保存的风格会出现在所有翻译模式的风格下拉里，随时选用。内置风格（{{ builtinNames }}）无需在此添加。
              </div>
              <el-table :data="presets" size="small" stripe empty-text="还没有自定义风格，点下方新增">
                <el-table-column label="名称" width="140">
                  <template slot-scope="{ row }"><el-input v-model="row.label" size="mini" maxlength="60" placeholder="如：影视字幕腔" /></template>
                </el-table-column>
                <el-table-column label="风格提示词">
                  <template slot-scope="{ row }"><el-input v-model="row.prompt" size="mini" type="textarea" :autosize="{minRows:1,maxRows:4}" maxlength="500" placeholder="描述期望的语气/用词/句式…" /></template>
                </el-table-column>
                <el-table-column width="90">
                  <template slot-scope="{ row, $index }">
                    <el-button type="text" :loading="row._saving" @click="savePreset(row)">保存</el-button>
                    <el-button type="text" class="del" @click="deletePreset(row, $index)">删</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-button size="small" icon="el-icon-plus" style="margin-top:10px" @click="addPreset">新增风格</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ==================== 账号与安全 ==================== -->
        <el-tab-pane label="账号与安全" name="account">
          <el-form label-width="120px" style="max-width:520px">
            <el-divider content-position="left">头像</el-divider>
            <el-form-item label="当前头像">
              <div class="avatar-row">
                <img :src="avatarSrc" class="avatar-preview" alt="头像">
                <div>
                  <el-button size="small" icon="el-icon-upload2" :loading="uploadingAvatar"
                             @click="$refs.avatarInput.click()">更换头像</el-button>
                  <el-button v-if="$store.state.avatar" size="small" type="text"
                             @click="clearAvatar">恢复默认</el-button>
                  <div class="tip">支持 jpg/png，选择后自动裁剪为正方形小图，仅保存在本机</div>
                </div>
                <input ref="avatarInput" type="file" accept="image/*" style="display:none" @change="onAvatarPick">
              </div>
            </el-form-item>

            <el-divider content-position="left">修改用户名</el-divider>
            <el-form-item label="当前用户名">
              <el-input :value="$store.state.username" disabled />
            </el-form-item>
            <el-form-item label="新用户名">
              <el-input v-model="uname.newUsername" maxlength="32" placeholder="2~32 个字符" />
            </el-form-item>
            <el-form-item label="登录密码">
              <el-input v-model="uname.password" type="password" show-password placeholder="验证身份" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingUname" @click="changeUsername">修改用户名</el-button>
            </el-form-item>

            <el-divider content-position="left">修改密码</el-divider>
            <el-form-item label="原密码">
              <el-input v-model="pwd.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwd.newPassword" type="password" show-password placeholder="至少 6 位" />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwd.confirm" type="password" show-password @keyup.enter.native="changePwd" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingPwd" @click="changePwd">修改密码</el-button>
            </el-form-item>

            <el-divider content-position="left">危险操作</el-divider>
            <el-form-item label="注销账号">
              <div class="danger-card">
                <div>
                  <b>永久注销此账号</b>
                  <div class="tip">账号将被停用且无法再登录，保存的 API 密钥会被清除。翻译任务与文件保留在本机 data 目录，可由管理员清理。</div>
                </div>
                <el-button type="danger" plain size="small" @click="delDialog = true">注销账号</el-button>
              </div>
            </el-form-item>
          </el-form>

          <el-dialog title="注销账号" :visible.sync="delDialog" width="420px" append-to-body>
            <p style="margin:0 0 12px">此操作<b>不可恢复</b>。注销后该用户名将无法再登录本系统。</p>
            <el-input v-model="delPassword" type="password" show-password placeholder="输入登录密码确认身份" />
            <el-checkbox v-model="delConfirm" style="margin-top:12px">我已了解注销后果，确认注销</el-checkbox>
            <div slot="footer">
              <el-button @click="delDialog = false">取消</el-button>
              <el-button type="danger" :disabled="!delConfirm || !delPassword" :loading="deleting"
                         @click="deleteAccount">确认注销</el-button>
            </div>
          </el-dialog>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script>
import http from '../api/http'
import { STYLE_PRESETS } from '../constants/styles'
import logo from '../assets/logo.png'

// 旧版设置页 tab 参数映射（外部深链兼容）
const TAB_ALIAS = { keys: 'api', agent: 'api', pwd: 'account', history: 'api' }

export default {
  name: 'Settings',
  data () {
    return {
      logo,
      tab: 'api',
      // ---- API 配置 ----
      svc: 'llm',
      llmSub: 'main',
      svcTypes: [
        { id: 'llm', icon: 'el-icon-chat-dot-round', name: '大语言模型', desc: '用于翻译、术语提取等' },
        { id: 'asr', icon: 'el-icon-microphone', name: '语音识别（ASR）', desc: '用于音频转写' },
        { id: 'tts', icon: 'el-icon-headset', name: '语音合成（TTS）', desc: '用于生成配音' }
      ],
      providers: [],
      services: [],
      svcForm: { id: null, provider: 'deepseek', baseUrl: '', apiKey: '', model: '', timeoutSec: 60, concurrency: 0 },
      editingSvc: null,
      svcModels: [],
      fetchingSvcModels: false,
      savingSvc: false,
      testingSvc: false,
      testResult: '',
      testOk: false,
      // ---- 通用设置（agent/tts/style）----
      settings: null,
      form: {
        ttsProvider: '', ttsBaseUrl: '', ttsApiKey: '', ttsModel: '',
        agentTranslateBaseUrl: '', agentTranslateApiKey: '', agentTranslateModel: '',
        agentMainBaseUrl: '', agentMainApiKey: '', agentMainModel: '',
        agentSubBaseUrl: '', agentSubApiKey: '', agentSubModel: '',
        searchProvider: '', searchBaseUrl: '', searchApiKey: '',
        langsmithApiKey: '', langsmithProject: '',
        stylePrompt: ''
      },
      // ---- ASR ----
      asrEngines: [],
      asrChoice: 'local',
      groqKeyInput: '',
      savingGroq: false,
      // ---- TTS ----
      ttsEngines: [],
      ttsModels: [],
      fetchingTtsModels: false,
      savingTts: false,
      // ---- Agent ----
      searchEngines: [],
      agentTranslateModels: [],
      fetchingAgentTranslate: false,
      agentMainModels: [],
      fetchingAgentMain: false,
      agentSubModels: [],
      fetchingAgentSub: false,
      savingAgent: false,
      testingLs: false,
      lsTestResult: '',
      // ---- 翻译偏好 ----
      presets: [],
      savingStyle: false,
      // ---- 账号与安全 ----
      uname: { newUsername: '', password: '' },
      savingUname: false,
      pwd: { oldPassword: '', newPassword: '', confirm: '' },
      savingPwd: false,
      uploadingAvatar: false,
      delDialog: false,
      delPassword: '',
      delConfirm: false,
      deleting: false
    }
  },
  computed: {
    builtinNames () { return STYLE_PRESETS.map(p => p.label).join('、') },
    allPresets () { return [...STYLE_PRESETS, ...this.presets.filter(p => p.label && p.prompt)] },
    currentProvider () {
      return this.providers.find(p => p.id === this.svcForm.provider) || null
    },
    isLlmProto () {
      const p = this.currentProvider
      return p && (p.protocol === 'openai' || p.protocol === 'claude')
    },
    defaultService () {
      return this.services.find(s => s.isDefault && s.enabled) || null
    },
    currentSearchEngine () {
      return this.searchEngines.find(e => e.id === this.form.searchProvider) || null
    },
    currentAsrEngine () {
      return this.asrEngines.find(e => e.id === this.asrChoice) || null
    },
    asrConfiguredCount () {
      return this.asrEngines.filter(e => e.configured).length
    },
    currentTtsEngine () {
      const id = this.settings && this.settings.ttsProvider
      return this.ttsEngines.find(e => e.id === id) || null
    },
    currentTtsSelected () {
      return this.ttsEngines.find(e => e.id === this.form.ttsProvider) || null
    },
    ttsConfiguredCount () {
      return this.ttsEngines.filter(e => e.configured).length
    },
    avatarSrc () {
      return this.$store.state.avatar || this.logo
    },
    agentModelRows () {
      const s = this.settings || {}
      const fallback = '沿用默认的大语言模型服务'
      const fmt = (base, model) => (base && model) ? `${model} @ ${base}` : fallback
      const search = this.currentSearchEngineName()
      return [
        { name: '翻译模型', status: fmt(s.agentTranslateBaseUrl, s.agentTranslateModel) },
        { name: '主 Agent（读稿与仲裁）', status: fmt(s.agentMainBaseUrl, s.agentMainModel) },
        { name: '子 Agent（领域专家）', status: fmt(s.agentSubBaseUrl, s.agentSubModel) },
        { name: '联网核实（搜索引擎）', status: search }
      ]
    },
    voiceModelRows () {
      const s = this.settings || {}
      const rows = []
      for (const e of this.asrEngines) {
        rows.push({
          svc: 'asr', sub: null, name: `语音识别 · ${e.name}`,
          status: !e.needKey ? '内置，恒可用' : (e.configured ? '已配置' : '未配置')
        })
      }
      const tts = this.currentTtsEngine
      rows.push({ svc: 'tts', sub: null, name: '语音合成（TTS）', status: tts ? `当前使用 ${tts.name}` : '未选择配音引擎' })
      return rows
    }
  },
  mounted () {
    const q = this.$route.query.tab
    if (q) {
      this.tab = TAB_ALIAS[q] || (['api', 'models', 'style', 'account'].includes(q) ? q : 'api')
      if (q === 'agent') this.llmSub = 'agent'
    }
    this.loadProviders()
    this.loadServices()
    this.loadSettings()
    this.loadAsrEngines()
    this.loadTtsEngines()
    this.loadSearchEngines()
    this.loadPresets()
  },
  methods: {
    // ---------- 帮助 ----------
    openHelp (doc) {
      const base = location.href.split('#')[0]
      window.open(base + '#/help' + (doc ? `?doc=${doc}` : ''), '_blank')
    },
    goto (tab, svc, sub) {
      this.tab = tab
      if (svc) this.svc = svc
      if (sub) this.llmSub = sub
    },
    fmtTime (t) {
      if (!t) return '—'
      return String(t).replace('T', ' ').slice(0, 16)
    },
    protoName (p) {
      return { openai: 'OpenAI', claude: 'Claude', deepl: '机翻', 'google-mt': '机翻', 'ms-mt': '机翻' }[p] || p
    },
    currentSearchEngineName () {
      const s = this.settings || {}
      if (!s.searchProvider || s.searchProvider === 'none') return '不联网'
      const e = this.searchEngines.find(x => x.id === s.searchProvider)
      return e ? e.name : s.searchProvider
    },
    ph (field) {
      const s = this.settings && this.settings[field]
      return s && s.set ? `已配置 ${s.masked}，留空不改` : '未配置'
    },

    // ---------- 数据加载 ----------
    loadProviders () {
      http.get('/settings/llm/providers').then(r => {
        this.providers = r.data || []
        if (!this.svcForm.id) this.onProviderChange(this.svcForm.provider)
      }).catch(() => {})
    },
    loadServices () {
      http.get('/settings/llm/services').then(r => { this.services = r.data || [] }).catch(() => {})
    },
    async loadSettings () {
      try {
        const r = await http.get('/settings')
        this.settings = r.data
        const d = r.data
        this.form.ttsProvider = d.ttsProvider || ''
        this.form.ttsBaseUrl = d.ttsBaseUrl || ''
        this.form.ttsModel = d.ttsModel || ''
        this.form.agentTranslateBaseUrl = d.agentTranslateBaseUrl || ''
        this.form.agentTranslateModel = d.agentTranslateModel || ''
        this.form.agentMainBaseUrl = d.agentMainBaseUrl || ''
        this.form.agentMainModel = d.agentMainModel || ''
        this.form.agentSubBaseUrl = d.agentSubBaseUrl || ''
        this.form.agentSubModel = d.agentSubModel || ''
        this.form.searchProvider = d.searchProvider || ''
        this.form.searchBaseUrl = d.searchBaseUrl || ''
        this.form.stylePrompt = d.stylePrompt || ''
        this.form.langsmithProject = d.langsmithProject || ''
        // ASR 默认选中：配了 Groq 就显示 Groq，否则本地
        if (d.groqApiKey && d.groqApiKey.set) this.asrChoice = 'groq'
      } catch (e) { /* ignore */ }
    },
    loadAsrEngines () {
      http.get('/settings/asr/engines').then(r => { this.asrEngines = r.data || [] }).catch(() => {})
    },
    loadTtsEngines () {
      http.get('/settings/tts/engines').then(r => { this.ttsEngines = r.data || [] }).catch(() => {})
    },
    loadSearchEngines () {
      http.get('/settings/search/engines').then(r => { this.searchEngines = r.data || [] }).catch(() => {})
    },
    async loadPresets () {
      try {
        const r = await http.get('/style-presets')
        this.presets = (r.data || []).map(p => ({ ...p, _saving: false }))
      } catch (e) { /* ignore */ }
    },

    // ---------- 模型服务 CRUD ----------
    onProviderChange (id) {
      const p = this.providers.find(x => x.id === id)
      if (!p) return
      // 新增或换服务商时预填默认端点与模型
      this.svcForm.baseUrl = p.defaultBaseUrl || ''
      this.svcForm.model = p.defaultModel || ''
      this.svcModels = []
      this.testResult = ''
    },
    resetSvcForm () {
      this.svcForm = { id: null, provider: 'deepseek', baseUrl: '', apiKey: '', model: '', timeoutSec: 60, concurrency: 0 }
      this.editingSvc = null
      this.svcModels = []
      this.testResult = ''
      this.onProviderChange('deepseek')
    },
    editSvc (row) {
      this.svcForm = {
        id: row.id,
        provider: row.provider,
        baseUrl: row.baseUrl || '',
        apiKey: '',
        model: row.model || '',
        timeoutSec: row.timeoutSec || 60,
        concurrency: row.concurrency || 0
      }
      this.editingSvc = row
      this.svcModels = row.model ? [row.model] : []
      this.testResult = ''
    },
    gotoEditSvc (row) {
      this.goto('api', 'llm', 'main')
      this.editSvc(row)
    },
    async saveSvc (makeDefault) {
      if (!this.svcForm.baseUrl && this.currentProvider && !this.currentProvider.defaultBaseUrl) {
        return this.$message.warning('请填写 Base URL')
      }
      if (!this.svcForm.id && !this.svcForm.apiKey.trim()) {
        return this.$message.warning('请填写 API Key')
      }
      this.savingSvc = true
      try {
        const inputBase = (this.svcForm.baseUrl || '').trim().replace(/\/+$/, '')
        const r = await http.post('/settings/llm/services', {
          id: this.svcForm.id,
          provider: this.svcForm.provider,
          baseUrl: this.svcForm.baseUrl,
          apiKey: this.svcForm.apiKey,
          model: this.svcForm.model,
          timeoutSec: this.svcForm.timeoutSec,
          concurrency: this.svcForm.concurrency,
          makeDefault: !!makeDefault
        })
        const saved = r.data
        if (saved && inputBase && saved.baseUrl !== inputBase) {
          this.$message.info(`Base URL 已自动补全：${saved.baseUrl}`)
        } else {
          this.$message.success('服务已保存')
        }
        this.loadServices()
        if (saved) this.editSvc(saved)
      } catch (e) { /* 拦截器已提示 */ } finally { this.savingSvc = false }
    },
    async testSvc () {
      this.testingSvc = true
      this.testResult = ''
      try {
        const r = await http.post('/settings/llm/test', {
          id: this.svcForm.id,
          provider: this.svcForm.provider,
          baseUrl: this.svcForm.baseUrl,
          apiKey: this.svcForm.apiKey,
          model: this.svcForm.model,
          timeoutSec: this.svcForm.timeoutSec,
          concurrency: this.svcForm.concurrency
        })
        this.testResult = r.data || '连接正常'
        this.testOk = true
      } catch (e) {
        this.testResult = (e && e.msg) ? `连接失败：${e.msg}` : '连接失败，请检查配置'
        this.testOk = false
      } finally { this.testingSvc = false }
    },
    async fetchSvcModels () {
      this.fetchingSvcModels = true
      try {
        const r = await http.post('/settings/llm/models', {
          baseUrl: this.svcForm.baseUrl,
          apiKey: this.svcForm.apiKey,
          protocol: this.currentProvider ? this.currentProvider.protocol : 'openai'
        })
        this.svcModels = r.data || []
        this.$message.success(`拉取到 ${this.svcModels.length} 个模型`)
      } catch (e) { /* 拦截器已提示 */ } finally { this.fetchingSvcModels = false }
    },
    svcRowCmd (cmd, row) {
      if (cmd === 'default') this.setDefaultSvc(row)
      else if (cmd === 'edit') this.editSvc(row)
      else if (cmd === 'del') this.deleteSvc(row)
    },
    async setDefaultSvc (row) {
      try {
        await http.post(`/settings/llm/services/${row.id}/default`)
        this.$message.success(`已把 ${row.providerName} 设为默认翻译服务`)
        this.loadServices()
      } catch (e) { /* ignore */ }
    },
    async toggleSvc (row, enabled) {
      try {
        await http.post(`/settings/llm/services/${row.id}/toggle`, { enabled })
        this.loadServices()
      } catch (e) { /* ignore */ }
    },
    deleteSvc (row) {
      this.$confirm(`确定删除服务「${row.providerName}${row.model ? ' · ' + row.model : ''}」？`, '删除服务', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
      }).then(async () => {
        await http.delete(`/settings/llm/services/${row.id}`)
        this.$message.success('已删除')
        if (this.svcForm.id === row.id) this.resetSvcForm()
        this.loadServices()
      }).catch(() => {})
    },

    // ---------- ASR ----------
    async saveGroqKey () {
      if (!this.groqKeyInput.trim()) return this.$message.warning('请填写 Groq API Key')
      this.savingGroq = true
      try {
        await http.put('/settings', { groqApiKey: this.groqKeyInput.trim() })
        this.$message.success('Groq 密钥已保存')
        this.groqKeyInput = ''
        this.loadSettings()
        this.loadAsrEngines()
      } catch (e) { /* ignore */ } finally { this.savingGroq = false }
    },

    // ---------- TTS ----------
    async saveTts () {
      if (!this.form.ttsProvider) return this.$message.warning('请先选择配音引擎')
      this.savingTts = true
      try {
        await http.put('/settings', {
          ttsProvider: this.form.ttsProvider,
          ttsBaseUrl: this.form.ttsBaseUrl,
          ttsApiKey: this.form.ttsApiKey,
          ttsModel: this.form.ttsModel
        })
        this.$message.success('语音合成配置已保存')
        this.form.ttsApiKey = ''
        this.loadSettings()
        this.loadTtsEngines()
      } catch (e) { /* ignore */ } finally { this.savingTts = false }
    },
    async fetchTtsModels () {
      this.fetchingTtsModels = true
      try {
        const r = await http.post('/settings/tts/models', {
          baseUrl: this.form.ttsBaseUrl,
          apiKey: this.form.ttsApiKey
        })
        this.ttsModels = r.data || []
        this.$message.success(`拉取到 ${this.ttsModels.length} 个模型`)
      } catch (e) { /* ignore */ } finally { this.fetchingTtsModels = false }
    },

    // ---------- Agent ----------
    async saveAgent () {
      this.savingAgent = true
      try {
        // 只提交本页字段，其余不传即不动（后端按字段判空写入）
        await http.put('/settings', {
          agentTranslateBaseUrl: this.form.agentTranslateBaseUrl,
          agentTranslateApiKey: this.form.agentTranslateApiKey,
          agentTranslateModel: this.form.agentTranslateModel,
          agentMainBaseUrl: this.form.agentMainBaseUrl,
          agentMainApiKey: this.form.agentMainApiKey,
          agentMainModel: this.form.agentMainModel,
          agentSubBaseUrl: this.form.agentSubBaseUrl,
          agentSubApiKey: this.form.agentSubApiKey,
          agentSubModel: this.form.agentSubModel,
          // 传空串是合法的：表示显式选择「不联网」
          searchProvider: this.form.searchProvider || '',
          searchBaseUrl: this.form.searchBaseUrl,
          searchApiKey: this.form.searchApiKey,
          langsmithApiKey: this.form.langsmithApiKey,
          langsmithProject: this.form.langsmithProject
        })
        this.$message.success('已保存')
        this.form.agentTranslateApiKey = this.form.agentMainApiKey = this.form.agentSubApiKey = ''
        this.form.searchApiKey = this.form.langsmithApiKey = ''
        this.loadSettings()
        this.loadSearchEngines()
      } catch (e) { /* ignore */ } finally { this.savingAgent = false }
    },
    async testLangsmith () {
      this.testingLs = true
      this.lsTestResult = ''
      try {
        // 表单填了用表单的（未保存也能测）；留空走已保存的。baseUrl 字段位借放项目名。
        const r = await http.post('/settings/langsmith/test', {
          apiKey: this.form.langsmithApiKey,
          baseUrl: this.form.langsmithProject
        })
        this.lsTestResult = r.data || ''
      } catch (e) { /* ignore */ } finally { this.testingLs = false }
    },
    async fetchAgentTranslateModels () {
      this.fetchingAgentTranslate = true
      try {
        const r = await http.post('/settings/agent-translate/models', {
          baseUrl: this.form.agentTranslateBaseUrl,
          apiKey: this.form.agentTranslateApiKey
        })
        this.agentTranslateModels = r.data || []
        this.$message.success(`拉取到 ${this.agentTranslateModels.length} 个模型`)
      } catch (e) { /* ignore */ } finally { this.fetchingAgentTranslate = false }
    },
    async fetchAgentMainModels () {
      this.fetchingAgentMain = true
      try {
        const r = await http.post('/settings/agent-main/models', {
          baseUrl: this.form.agentMainBaseUrl,
          apiKey: this.form.agentMainApiKey
        })
        this.agentMainModels = r.data || []
        this.$message.success(`拉取到 ${this.agentMainModels.length} 个模型`)
      } catch (e) { /* ignore */ } finally { this.fetchingAgentMain = false }
    },
    async fetchAgentSubModels () {
      this.fetchingAgentSub = true
      try {
        const r = await http.post('/settings/agent-sub/models', {
          baseUrl: this.form.agentSubBaseUrl,
          apiKey: this.form.agentSubApiKey
        })
        this.agentSubModels = r.data || []
        this.$message.success(`拉取到 ${this.agentSubModels.length} 个模型`)
      } catch (e) { /* ignore */ } finally { this.fetchingAgentSub = false }
    },

    // ---------- 翻译偏好 ----------
    async saveStylePrompt () {
      this.savingStyle = true
      try {
        await http.put('/settings', { stylePrompt: (this.form.stylePrompt || '').trim() })
        this.$message.success('默认风格已保存')
        this.loadSettings()
      } catch (e) { /* ignore */ } finally { this.savingStyle = false }
    },
    addPreset () {
      this.presets.push({ id: null, label: '', prompt: '', _saving: false })
    },
    async savePreset (row) {
      if (!row.label || !row.label.trim()) return this.$message.warning('请填风格名称')
      if (!row.prompt || !row.prompt.trim()) return this.$message.warning('请填风格提示词')
      row._saving = true
      try {
        if (row.id) {
          await http.put(`/style-presets/${row.id}`, { label: row.label, prompt: row.prompt })
        } else {
          const r = await http.post('/style-presets', { label: row.label, prompt: row.prompt })
          row.id = r.data.id
        }
        this.$message.success('已保存')
      } catch (e) { /* 拦截器已提示 */ } finally { row._saving = false }
    },
    async deletePreset (row, idx) {
      if (!row.id) { this.presets.splice(idx, 1); return }
      try {
        await http.delete(`/style-presets/${row.id}`)
        this.presets.splice(idx, 1)
        this.$message.success('已删除')
      } catch (e) { /* ignore */ }
    },

    // ---------- 账号与安全 ----------
    async changeUsername () {
      if (!this.uname.newUsername.trim()) return this.$message.warning('请填写新用户名')
      if (!this.uname.password) return this.$message.warning('请输入登录密码验证身份')
      this.savingUname = true
      try {
        const r = await http.post('/auth/change-username', {
          newUsername: this.uname.newUsername.trim(),
          password: this.uname.password
        })
        // 后端签发了新 token（旧 token 的 subject 是旧用户名），立即替换本地登录态
        this.$store.commit('setAuth', { token: r.data.token, username: r.data.username })
        this.uname = { newUsername: '', password: '' }
        this.$message.success('用户名已修改')
      } catch (e) { /* 拦截器已提示 */ } finally { this.savingUname = false }
    },
    async changePwd () {
      if (!this.pwd.oldPassword || !this.pwd.newPassword) return this.$message.warning('请填写密码')
      if (this.pwd.newPassword.length < 6) return this.$message.warning('新密码至少 6 位')
      if (this.pwd.newPassword !== this.pwd.confirm) return this.$message.warning('两次新密码不一致')
      this.savingPwd = true
      try {
        await http.post('/auth/change-password', {
          oldPassword: this.pwd.oldPassword, newPassword: this.pwd.newPassword
        })
        this.$message.success('密码已修改')
        this.pwd = { oldPassword: '', newPassword: '', confirm: '' }
      } catch (e) { /* ignore */ } finally { this.savingPwd = false }
    },
    onAvatarPick (e) {
      const file = e.target.files && e.target.files[0]
      e.target.value = ''
      if (!file) return
      if (!/^image\//.test(file.type)) return this.$message.warning('请选择图片文件')
      const reader = new FileReader()
      reader.onload = () => this.cropAndUpload(reader.result)
      reader.readAsDataURL(file)
    },
    cropAndUpload (dataUrl) {
      const img = new Image()
      img.onload = async () => {
        // 居中裁剪成 256×256 正方形，压成 jpeg 控制体积
        const size = 256
        const canvas = document.createElement('canvas')
        canvas.width = canvas.height = size
        const ctx = canvas.getContext('2d')
        const min = Math.min(img.width, img.height)
        const sx = (img.width - min) / 2
        const sy = (img.height - min) / 2
        ctx.drawImage(img, sx, sy, min, min, 0, 0, size, size)
        const out = canvas.toDataURL('image/jpeg', 0.85)
        this.uploadingAvatar = true
        try {
          const r = await http.post('/auth/avatar', { avatar: out })
          this.$store.commit('setAvatar', r.data || out)
          this.$message.success('头像已更新')
        } catch (err) { /* 拦截器已提示 */ } finally { this.uploadingAvatar = false }
      }
      img.onerror = () => this.$message.warning('图片读取失败，请换一张')
      img.src = dataUrl
    },
    async clearAvatar () {
      this.uploadingAvatar = true
      try {
        await http.post('/auth/avatar', { avatar: '' })
        this.$store.commit('setAvatar', '')
        this.$message.success('已恢复默认头像')
      } catch (e) { /* ignore */ } finally { this.uploadingAvatar = false }
    },
    async deleteAccount () {
      this.deleting = true
      try {
        await http.post('/auth/delete-account', { password: this.delPassword })
        this.delDialog = false
        this.$message.success('账号已注销')
        this.$store.commit('logout')
        this.$router.push('/login')
      } catch (e) { /* 拦截器已提示 */ } finally { this.deleting = false }
    }
  }
}
</script>

<style scoped>
.body { max-width: 1080px; margin: 0 auto; }
.head-row { display: flex; justify-content: space-between; align-items: flex-start; max-width: 1080px; margin: 0 auto; }
.tip { color: #999; font-size: 12px; line-height: 1.6; }
.tip code { background: #f0f2f5; padding: 1px 5px; border-radius: 3px; font-size: 12px; }

/* ---- API 配置横幅 ---- */
.api-banner {
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
  background: #f4f7ff; border: 1px solid #e3eaff; border-radius: 10px;
  padding: 12px 16px; margin-bottom: 16px; font-size: 13px; color: #4a5568;
}
.api-banner i { color: var(--brand, #4c7dff); margin-right: 6px; }

/* ---- 左侧服务类型 ---- */
.api-layout { display: flex; gap: 18px; align-items: flex-start; }
.svc-side {
  width: 210px; flex-shrink: 0; background: #fff;
  border: 1px solid #eef1f6; border-radius: 12px; padding: 12px 10px;
}
.svc-side-title { color: #a0aec0; font-size: 12px; margin: 2px 6px 10px; }
.svc-item {
  display: flex; align-items: flex-start; gap: 10px; cursor: pointer;
  padding: 10px; border-radius: 10px; margin-bottom: 4px;
}
.svc-item i { font-size: 17px; color: #7b8794; margin-top: 2px; }
.svc-item:hover { background: #f5f7fb; }
.svc-item.active { background: #eef2ff; }
.svc-item.active i, .svc-item.active .svc-name { color: var(--brand-deep, #3b66f5); }
.svc-meta { display: flex; flex-direction: column; line-height: 1.4; }
.svc-name { font-size: 14px; font-weight: 600; color: #1f2d3d; }
.svc-desc { font-size: 12px; color: #a0aec0; }

/* ---- 右侧面板 ---- */
.svc-panel {
  flex: 1; min-width: 0; background: #fff;
  border: 1px solid #eef1f6; border-radius: 12px; padding: 16px 20px 20px;
}
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.panel-title { font-size: 15px; font-weight: 700; color: #1f2d3d; display: inline-flex; align-items: center; gap: 8px; }
.panel-actions { display: inline-flex; align-items: center; gap: 10px; }
.svc-form { max-width: 680px; }
.test-result {
  margin: 8px 0 0; padding: 8px 12px; border-radius: 8px; font-size: 12px;
  background: #fef0f0; color: #f56c6c; border: 1px solid #fde2e2;
}
.test-result.ok { background: #f0f9eb; color: #67c23a; border-color: #e1f3d8; }

/* ---- 已配置服务列表 ---- */
.svc-list-head {
  display: flex; justify-content: space-between; align-items: center;
  margin: 22px 0 10px;
}
.empty-tip {
  color: #a0aec0; font-size: 13px; background: #fafbfc;
  border: 1px dashed #e4e7ed; border-radius: 10px; padding: 18px; text-align: center;
}
.svc-row {
  display: flex; align-items: center; gap: 10px;
  border: 1px solid #eef1f6; border-radius: 10px;
  padding: 10px 14px; margin-bottom: 8px; background: #fff;
}
.svc-row.off { opacity: .55; }
.svc-logo {
  width: 30px; height: 30px; border-radius: 8px; flex-shrink: 0;
  background: linear-gradient(120deg, #4c7dff, #7c5cff); color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 14px; font-weight: 700;
}
.svc-row-name { font-weight: 600; font-size: 14px; color: #1f2d3d; }
.svc-model { max-width: 220px; overflow: hidden; text-overflow: ellipsis; }
.svc-row-time { margin-left: auto; color: #a0aec0; font-size: 12px; }
.svc-more { cursor: pointer; color: #7b8794; padding: 4px; }

.local-asr-card {
  display: flex; gap: 12px; align-items: flex-start;
  background: #f0f9eb; border: 1px solid #e1f3d8; border-radius: 10px;
  padding: 12px 14px; font-size: 13px; color: #333; max-width: 560px;
}
.local-asr-card i { font-size: 22px; color: #67c23a; margin-top: 2px; }

/* ---- 引擎卡片（搜索引擎复用） ---- */
.engine-list { display: flex; flex-direction: column; gap: 8px; }
.engine-card {
  border: 1px solid #e4e7ed; border-radius: 10px; padding: 10px 14px;
  cursor: pointer; transition: border-color .15s, background .15s;
}
.engine-card:hover { border-color: #b9c6ff; }
.engine-card.active { border-color: var(--brand, #4c7dff); background: #f4f7ff; }
.engine-head { display: flex; align-items: center; gap: 8px; }
.engine-name { font-weight: 600; font-size: 14px; color: #1f2d3d; }
.engine-desc { color: #8492a6; font-size: 12px; margin-top: 4px; line-height: 1.6; }

.adv-collapse { margin-top: 14px; }

/* ---- 风格预设 ---- */
.style-presets { margin-bottom: 8px; }
.style-tag { cursor: pointer; margin: 0 6px 6px 0; }

/* ---- 账号 ---- */
.avatar-row { display: flex; align-items: center; gap: 16px; }
.avatar-preview {
  width: 64px; height: 64px; border-radius: 50%; object-fit: cover;
  border: 1px solid #eef1f6; background: #fff;
}
.danger-card {
  display: flex; justify-content: space-between; align-items: center; gap: 14px;
  border: 1px solid #fde2e2; background: #fef7f7; border-radius: 10px;
  padding: 12px 14px;
}
.del { color: #f56c6c; }
</style>
