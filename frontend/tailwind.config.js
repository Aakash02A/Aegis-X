export default {
  content: ['./*.html', './js/**/*.js'],
  theme: {
    extend: {
      colors: {
        paper: '#F8F6F0',
        'paper-dim': '#EFECE3',
        ink: '#141210',
        'ink-deep': '#0D0C0A',
        'ink-soft': '#736E65',
        rust: '#C85A32',
        brass: '#C8A86B',
        'olive-deep': '#5A5E43'
      },
      fontFamily: {
        serif: ['Fraunces', 'Playfair Display', 'Georgia', 'serif'],
        display: ['Urbanist', 'Work Sans', 'sans-serif'],
        sans: ['Work Sans', 'Inter', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace']
      }
    }
  },
  plugins: []
};
