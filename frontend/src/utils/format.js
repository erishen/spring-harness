/**
 * 消息/工具展示相关的共享格式化工具
 * 被 MessageList、MessageItem、ToolCallCard、LongTaskPanel 等组件复用
 */

/** 工具名 → 展示名映射（未知工具保留原名） */
const TOOL_NAMES = {
  calculator: '🧮 计算器',
  get_datetime: '🕐 当前时间',
  query_stock: '📈 股票查询',
  execute_code: '💻 代码执行',
  skill_run: '📦 技能加载',
  knowledge_search: '📚 知识库检索',
  memory_search: '🧠 记忆检索',
  delegate_specialist: '👤 委派 Specialist',
  mcp_filesystem: '📁 MCP 文件系统',
  portfolio_check: '📊 持仓体检',
  pse_review: '🔍 PSE 复盘'
}

export function toolDisplayName(name) {
  return TOOL_NAMES[name] || name
}

/** PSE 角色名 → 展示名 */
export function roleDisplayName(role) {
  const names = {
    planner: 'Planner 规划者',
    specialist: 'Specialist 执行者',
    evaluator: 'Evaluator 评审官',
    system: 'System',
    step: 'Step',
    thinking: 'Thinking',
    tool_call: 'Tool Call',
    tool_result: 'Tool Result',
    answer: 'Answer',
    done: 'Done',
    error: 'Error'
  }
  return names[role] || role
}

/** 格式化工具入参：对象 → JSON，字符串保留 */
export function formatInput(input) {
  if (!input) return '(无)'
  if (typeof input === 'string') return input
  try { return JSON.stringify(input, null, 2) } catch { return String(input) }
}

/** 格式化工具结果：解析 Response[...] 包装，否则原样 */
export function formatOutput(output) {
  if (!output) return '(无)'
  if (typeof output !== 'string') {
    try { return JSON.stringify(output, null, 2) } catch { return String(output) }
  }
  const match = output.match(/Response\[(.*)\]/s)
  if (match) return match[1].replace(/, /g, '\n')
  return output
}

/**
 * 构建 PSE 错误报告（正确步骤简写，出错/最后一步详细）
 * @param {{content?:string, pseSteps?:Array, tokenUsage?:Object, llmCallCount?:number}} msg
 * @returns {string}
 */
export function buildErrorReport(msg) {
  const lines = []
  lines.push('=== PSE 执行错误报告 ===')
  lines.push('')
  lines.push('【错误信息】')
  lines.push(msg.content || '未知错误')
  lines.push('')
  if (msg.pseSteps && msg.pseSteps.length) {
    lines.push(`【PSE 执行步骤】共 ${msg.pseSteps.length} 步（正确步骤简写，出错步骤详细）`)
    lines.push('')
    const lastIdx = msg.pseSteps.length - 1
    msg.pseSteps.forEach((step, i) => {
      const isLast = i === lastIdx
      const hasError = step.content && /错误|失败|Exception|Error|timeout|超时/i.test(step.content)
      if (!isLast && !hasError) {
        lines.push(`✓ 步骤 ${i + 1}: [${step.role}] ${step.title}`)
      } else {
        lines.push(`✗ 步骤 ${i + 1}: [${step.role}] ${step.title} ${isLast ? '← 最后一步' : ''}`)
        if (step.content) {
          lines.push('  内容:')
          step.content.split('\n').forEach(l => lines.push('  ' + l))
        }
        if (step.toolCalls && step.toolCalls.length) {
          step.toolCalls.forEach(tc => {
            lines.push(`  [工具调用] ${tc.name} (${tc.durationMs || '?'}ms)`)
            if (tc.input) lines.push(`  入参: ${JSON.stringify(tc.input)}`)
            if (tc.output) lines.push(`  结果: ${tc.output}`)
          })
        }
      }
      lines.push('')
    })
  }
  if (msg.tokenUsage) {
    lines.push('【Token 用量】')
    lines.push(`总: ${msg.tokenUsage.totalTokens}, 输入: ${msg.tokenUsage.promptTokens}, 输出: ${msg.tokenUsage.completionTokens}`)
    if (msg.llmCallCount) lines.push(`LLM 调用次数: ${msg.llmCallCount}`)
  }
  return lines.join('\n')
}

/** 复制文本到剪贴板（含降级方案） */
export async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    try {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      document.body.removeChild(textarea)
      return true
    } catch {
      return false
    }
  }
}
