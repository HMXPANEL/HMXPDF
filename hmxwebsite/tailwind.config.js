/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./*.html', './assets/js/**/*.js'],
  theme: {
    extend: {
      colors: {
        night: {
          950: '#06050B',
          900: '#0A0812',
          850: '#0E0B18',
          800: '#12101E',
          700: '#1A1628'
        },
        accent: {
          DEFAULT: '#A855F7',
          light: '#C084FC',
          soft: '#D8B4FE',
          dark: '#7C3AED'
        },
        mist: {
          DEFAULT: '#A9A2BE',
          bright: '#F4F1FB',
          dim: '#7A7390'
        }
      },
      fontFamily: {
        sans: [
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'Helvetica Neue',
          'Arial',
          'sans-serif'
        ]
      },
      boxShadow: {
        glow: '0 0 60px -18px rgba(168, 85, 247, 0.7)',
        'glow-sm': '0 0 28px -12px rgba(168, 85, 247, 0.65)',
        phone: '0 40px 90px -25px rgba(0, 0, 0, 0.85)'
      },
      keyframes: {
        rise: {
          from: { opacity: '0', transform: 'translateY(22px)' },
          to: { opacity: '1', transform: 'translateY(0)' }
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-10px)' }
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' }
        }
      },
      animation: {
        rise: 'rise 0.7s cubic-bezier(0.22, 1, 0.36, 1) both',
        float: 'float 7s ease-in-out infinite',
        shimmer: 'shimmer 1.6s linear infinite'
      }
    }
  },
  plugins: []
};
