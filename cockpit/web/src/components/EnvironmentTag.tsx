import { useI18n } from '../i18n/I18nProvider';

/** The environment as configured, written in one form everywhere: its first letter upper case ("Production"). */
export function environmentLabel(environment: string, locale: string): string {
  const first = environment.charAt(0);
  return first.toLocaleUpperCase(locale) + environment.slice(1);
}

/**
 * The environment tag of the service pill. Amber is kept for the Demo tag alone (styleguide section 2); every
 * other environment, Production included, uses the neutral environment tag tokens.
 */
export function EnvironmentTag({ environment }: { environment: string }) {
  const { locale } = useI18n();
  const demo = environment.trim().toLowerCase() === 'demo';
  return (
    <span className={demo ? 'tag tag--demo' : 'tag tag--env'} data-testid="environment-tag" aria-hidden="true">
      {environmentLabel(environment, locale)}
    </span>
  );
}
