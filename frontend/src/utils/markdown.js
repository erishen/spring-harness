/**
 * Markdown 渲染工具：统一配置 marked + 代码高亮 + 代码块增强。
 * 供 MessageList / LongTaskPanel 等组件复用。
 *
 * 性能优化：按需引入 highlight.js core + 常用语言，避免整包（190+ 语言）拖大 bundle。
 */
import { marked } from 'marked'
import { markedHighlight } from 'marked-highlight'
import hljs from 'highlight.js/lib/core'
import 'highlight.js/styles/github.css'

// 常用语言（覆盖 LLM 主要输出），按需注册
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import python from 'highlight.js/lib/languages/python'
import java from 'highlight.js/lib/languages/java'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import go from 'highlight.js/lib/languages/go'
import rust from 'highlight.js/lib/languages/rust'
import kotlin from 'highlight.js/lib/languages/kotlin'
import swift from 'highlight.js/lib/languages/swift'
import json from 'highlight.js/lib/languages/json'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import bash from 'highlight.js/lib/languages/bash'
import shell from 'highlight.js/lib/languages/shell'
import sql from 'highlight.js/lib/languages/sql'
import yaml from 'highlight.js/lib/languages/yaml'
import markdown from 'highlight.js/lib/languages/markdown'
import diff from 'highlight.js/lib/languages/diff'
import plaintext from 'highlight.js/lib/languages/plaintext'

hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('java', java)
hljs.registerLanguage('c', c)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('csharp', csharp)
hljs.registerLanguage('go', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('kotlin', kotlin)
hljs.registerLanguage('swift', swift)
hljs.registerLanguage('json', json)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('shell', shell)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('diff', diff)
hljs.registerLanguage('plaintext', plaintext)
// 别名：html/vue 实际语法是 xml；ts 映射 typescript
hljs.registerAliases(['html', 'vue', 'jsx', 'tsx'], { languageName: 'xml' })
hljs.registerAliases(['ts'], { languageName: 'typescript' })
hljs.registerAliases(['js', 'node'], { languageName: 'javascript' })
hljs.registerAliases(['py'], { languageName: 'python' })
hljs.registerAliases(['sh'], { languageName: 'bash' })

// 配置 Markdown 解析 + 代码高亮
marked.use(markedHighlight({
  langPrefix: 'hljs language-',
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang }).value
    }
    return hljs.highlightAuto(code).value
  }
}))
marked.setOptions({
  breaks: true,
  gfm: true
})

/**
 * 渲染 Markdown 为 HTML 字符串。
 * 预处理：修复 LLM 生成的不规范语法（标题/列表后缺空格、加粗星号位置错误）。
 */
export function renderMarkdown(content) {
  if (!content) return ''
  let processed = content
  // 标题后缺空格：##标题 → ## 标题
  processed = processed.replace(/^(#{1,6})([^#\s])/gm, '$1 $2')
  // 列表后缺空格：-列表 → - 列表
  processed = processed.replace(/^([*\-])([^\s])/gm, '$1 $2')
  processed = processed.replace(/^(\d+\.)([^\s])/gm, '$1 $2')
  // 不规范加粗：行首 "词*：" 或 "词* " → "**词**：" 或 "**词** "
  // 匹配中文/字母/数字组成的词，后跟单个星号+冒号/空格
  processed = processed.replace(/^([\u4e00-\u9fa5a-zA-Z0-9]{1,10})\*([：:\s])/gm, '**$1**$2')
  // 不规范加粗：行内 "词*：" → "**词**："（星号前是中文/字母，星号后是冒号）
  processed = processed.replace(/([\u4e00-\u9fa5a-zA-Z])\*([：:])/g, '**$1**$2')
  return marked.parse(processed)
}

/**
 * 给容器内所有裸 <pre> 添加代码块包装（语言标签 + 复制按钮）。
 * 幂等：已有 wrapper 的跳过。复制按钮点击后显示"已复制"。
 */
export function enhanceCodeBlocks(container) {
  if (!container) return
  container.querySelectorAll('pre').forEach((pre) => {
    // 避免重复包装
    if (pre.parentElement?.classList.contains('code-block-wrapper')) return

    const code = pre.querySelector('code')
    const langMatch = code?.className?.match(/language-([\w+-]+)/)
    const lang = langMatch ? langMatch[1] : ''

    const wrapper = document.createElement('div')
    wrapper.className = 'code-block-wrapper'

    const header = document.createElement('div')
    header.className = 'code-block-header'

    const langLabel = document.createElement('span')
    langLabel.className = 'code-block-lang'
    langLabel.textContent = lang || 'text'

    const copyBtn = document.createElement('button')
    copyBtn.className = 'code-copy-btn'
    copyBtn.textContent = '复制'
    copyBtn.addEventListener('click', (e) => {
      e.stopPropagation()
      const text = pre.innerText
      const done = () => {
        copyBtn.textContent = '✓ 已复制'
        copyBtn.classList.add('copied')
        setTimeout(() => {
          copyBtn.textContent = '复制'
          copyBtn.classList.remove('copied')
        }, 1500)
      }
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done))
      } else {
        fallbackCopy(text, done)
      }
    })

    header.appendChild(langLabel)
    header.appendChild(copyBtn)

    pre.parentElement?.insertBefore(wrapper, pre)
    wrapper.appendChild(header)
    wrapper.appendChild(pre)
  })
}

function fallbackCopy(text, done) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  document.execCommand('copy')
  document.body.removeChild(ta)
  done()
}
