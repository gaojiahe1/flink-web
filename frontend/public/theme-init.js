// Run before styles load so refreshes never flash the opposite theme.
;(function () {
  var theme = 'mist'
  try {
    var saved = localStorage.getItem('flink-ui-theme')
    if (saved === 'mist' || saved === 'dark' || saved === 'pearl') theme = saved
  } catch (_) {}
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme === 'dark' ? 'dark' : 'light'
})()
