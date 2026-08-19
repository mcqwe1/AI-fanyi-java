<template>
  <el-select :value="value" multiple collapse-tags style="width:100%"
             placeholder="选择要套用的术语库" @input="onInput">
    <el-option :key="0" label="无" :value="0" />
    <el-option v-for="p in projects" :key="p.id"
               :label="p.name + '（' + p.termCount + ' 条）'" :value="p.id" />
  </el-select>
</template>

<script>
import http from '../api/http'

/**
 * 术语库多选下拉（2026-08 需求改版）：选中术语库里的术语会以提示词注入本次翻译。
 * 「无」(value=0) 与具体术语库互斥——后选者生效；空选自动回落到「无」。
 * v-model 为 id 数组，默认 [0]。提交时过滤掉 0 再 join(',') 传 glossaryProjectIds。
 */
export default {
  name: 'GlossaryPicker',
  props: {
    value: { type: Array, default: () => [0] }
  },
  data () {
    return { projects: [] }
  },
  async created () {
    try {
      const r = await http.get('/kb/projects')
      this.projects = r.data || []
    } catch (e) { /* 列表拉取失败不阻塞页面，仅剩「无」可选 */ }
  },
  methods: {
    onInput (val) {
      let v = val || []
      if (v.length > 1) {
        const pickedNone = v[v.length - 1] === 0
        v = pickedNone ? [0] : v.filter(x => x !== 0)
      }
      if (v.length === 0) v = [0]
      this.$emit('input', v)
    }
  }
}
</script>
