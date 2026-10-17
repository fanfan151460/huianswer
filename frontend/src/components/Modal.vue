<script setup>
import { ref, watch, nextTick } from 'vue'
import Icon from './Icon.vue'
const props = defineProps({ open: Boolean, title: String, wide: Boolean, busy: Boolean })
const emit = defineEmits(['close'])
const dialog = ref(null)
watch(() => props.open, async open => { await nextTick(); if (open && !dialog.value.open) dialog.value.showModal(); else if (!open && dialog.value.open) dialog.value.close() })
</script>
<template><dialog ref="dialog" :class="['modal', { wide }]" @cancel.prevent="!busy && emit('close')" @click="e => e.target === dialog && !busy && emit('close')">
  <header class="modal-head"><h2>{{ title }}</h2><button class="icon-button" aria-label="关闭弹窗" :disabled="busy" @click="emit('close')"><Icon name="close"/></button></header>
  <slot />
</dialog></template>
