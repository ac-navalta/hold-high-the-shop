/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        uc: {
          green: '#07683B',
          greenDark: '#05512E',
          cream: '#F9F6E7',
          mint: '#C1D9CE',
          mintLight: '#E4EFEA',
          divider: '#E6E2D2',
          swapBg: '#FFF5DF',
          swapText: '#8A5A00',
          danger: '#C62828',
          dangerLight: '#FDECEA',
        },
      },
      fontFamily: {
        sans: ['Poppins', 'system-ui', '-apple-system', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
