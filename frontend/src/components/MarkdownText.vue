<script setup>
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'
const props = defineProps({ content: { type: String, default: '' }, sourceCount: { type: Number, default: 0 } })
const emit = defineEmits(['citation'])
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
const rendered = computed(() => {
  const root = document.createElement('div')
  root.innerHTML = DOMPurify.sanitize(md.render(props.content))
  root.querySelectorAll('a').forEach(a => { a.target = '_blank'; a.rel = 'noopener noreferrer' })
  if (props.sourceCount) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT), nodes = []
    while (walker.nextNode()) if (!walker.currentNode.parentElement.closest('a, code, pre, button')) nodes.push(walker.currentNode)
    for (const node of nodes) {
      const pieces = [...node.textContent.matchAll(/\[(\d+)\]/g)]; if (!pieces.length) continue
      const fragment = document.createDocumentFragment(); let offset = 0
      for (const match of pieces) {
        fragment.append(node.textContent.slice(offset, match.index))
        const index = Number(match[1])
        if (index > 0 && index <= props.sourceCount) {
          const button = document.createElement('button'); button.type = 'button'; button.className = 'citation'
          button.dataset.citation = index; button.textContent = index; button.setAttribute('aria-label', `查看引用 ${index}`)
          fragment.append(button)
        } else fragment.append(match[0])
        offset = match.index + match[0].length
      }
      fragment.append(node.textContent.slice(offset)); node.replaceWith(fragment)
    }
  }
  return root.innerHTML
})
function click(event) { const button = event.target.closest('[data-citation]'); if (button) emit('citation', Number(button.dataset.citation)) }
</script>
<template><div class="markdown" v-html="rendered" @click="click" /></template>
