<script setup>
defineProps({ name: { type: String, default: 'spark' }, size: { type: [Number, String], default: 20 } })
const paths = {
  plus:'M12 5v14M5 12h14', arrow:'M12 19V5m-6 6 6-6 6 6', chevron:'m9 5 7 7-7 7', down:'m6 9 6 6 6-6',
  chat:'M21 11.5a8.4 8.4 0 0 1-.9 3.8A8.5 8.5 0 0 1 12.5 20a8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8A8.5 8.5 0 0 1 12.5 3h.5a8.5 8.5 0 0 1 8 8v.5Z',
  book:'M12 7v14m0-14C9 4 5 3 2 4v15c4-1 7 0 10 2 3-2 6-3 10-2V4c-3-1-7 0-10 3Z',
  search:'m21 21-4.5-4.5M19 10.5a8.5 8.5 0 1 1-17 0 8.5 8.5 0 0 1 17 0Z',
  logout:'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4m7 14 5-5-5-5m5 5H9',
  menu:'M3 6h18M3 12h18M3 18h18', close:'m6 6 12 12M6 18 18 6', more:'M5 12h.01M12 12h.01M19 12h.01',
  edit:'m16 3 5 5M4 20l5-1L21 7a2.1 2.1 0 0 0-5-5L4 14Z',
  trash:'M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6m4-6v6',
  file:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Zm0 0v6h6M8 13h8m-8 4h5',
  upload:'M12 16V3m-5 5 5-5 5 5M3 16v4h18v-4',
  check:'m5 12 4 4L19 6', stop:'M6 6h12v12H6z', copy:'M9 9h12v12H9zM5 15H3V3h12v2',
  refresh:'M20 7v5h-5M4 17v-5h5M6.1 6.1a8 8 0 0 1 13.2 3M4.7 14.9a8 8 0 0 0 13.2 3',
  shield:'M12 22s8-4 8-11V4l-8-3-8 3v7c0 7 8 11 8 11Zm-4-11 3 3 5-6',
  link:'m10 13 4-4M8 16l-2 2a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0m2 1 2-2a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0',
  eye:'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Zm13 0a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  folder:'M3 4h6l2 3h10v13H3z', clock:'M12 8v5l3 2m6-3a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  spark:'m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z',
  info:'M12 11v6m0-10h.01m9 5a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  mail:'M3 5h18v14H3Zm0 0 9 8 9-8', layers:'m12 2 10 5-10 5L2 7Zm-10 10 10 5 10-5M2 17l10 5 10-5',
  keyboard:'M2 5h20v14H2ZM6 9h.01M10 9h.01M14 9h.01M18 9h.01M6 13h.01M9 16h6m3-3h.01'
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.spark" /></svg></template>
