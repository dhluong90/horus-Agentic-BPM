import { LOCALES, useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../preferences/theme';

const LOCALE_LABEL = { en: 'EN', vi: 'VI' } as const;
const LOCALE_NAME = { en: 'English', vi: 'Tiếng Việt' } as const;

/** EN | VI switch and the theme switch. One language is shown at a time. */
export function PreferenceControls() {
  const { locale, setLocale, t } = useI18n();
  const { theme, toggle } = useTheme();
  const themeLabel = theme === 'dark' ? t('theme.toLight') : t('theme.toDark');
  return (
    <div className="prefs">
      <div className="seg" role="group" aria-label={t('header.language')}>
        {LOCALES.map((option) => (
          <button
            key={option}
            type="button"
            className="hit"
            lang={option}
            aria-pressed={locale === option}
            title={LOCALE_NAME[option]}
            onClick={() => {
              setLocale(option);
            }}
          >
            {LOCALE_LABEL[option]}
          </button>
        ))}
      </div>
      <button type="button" className="iconbtn hit" onClick={toggle} aria-label={themeLabel} title={themeLabel}>
        {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
      </button>
    </div>
  );
}

function MoonIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
      <path d="M10 3a7 7 0 1 0 7 7 5.5 5.5 0 0 1-7-7z" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" />
    </svg>
  );
}

function SunIcon() {
  return (
    <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
      <circle cx="10" cy="10" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.6" />
      <path
        d="M10 2.5v2M10 15.5v2M2.5 10h2M15.5 10h2M4.7 4.7l1.4 1.4M13.9 13.9l1.4 1.4M4.7 15.3l1.4-1.4M13.9 6.1l1.4-1.4"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
    </svg>
  );
}
