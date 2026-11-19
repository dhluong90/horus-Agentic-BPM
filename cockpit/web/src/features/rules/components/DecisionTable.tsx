import type { RuleSetDefinition } from '../../../api/rules/schemas';
import { useI18n } from '../../../i18n/I18nProvider';
import { columnLabel, readCondition, readOutput } from '../model/reading';
import { CellValue } from './CellValue';

/** Column headers shared by the table, the editor and the diff: two groups, When and Then. */
export function TableHead({ definition, extra }: { definition: RuleSetDefinition; extra?: string }) {
  const { t } = useI18n();
  return (
    <thead>
      <tr className="dt__groups">
        <th scope="col" rowSpan={2} className="dt__rowhead">{t('table.row')}</th>
        <th scope="colgroup" colSpan={definition.inputs.length} className="dt__group dt__group--when">{t('table.when')}</th>
        <th scope="colgroup" colSpan={definition.outputs.length} className="dt__group dt__group--then">{t('table.then')}</th>
        {extra !== undefined ? <th scope="col" rowSpan={2} className="dt__actions-head">{extra}</th> : null}
      </tr>
      <tr>
        {definition.inputs.map((input) => (
          <th key={`in-${input.name}`} scope="col">{columnLabel(input.name, input.description)}</th>
        ))}
        {definition.outputs.map((output, index) => (
          <th key={`out-${output.name}`} scope="col" className={index === 0 ? 'dt__first-then' : undefined}>
            {columnLabel(output.name, output.description)}
          </th>
        ))}
      </tr>
    </thead>
  );
}

/** The rule set as a decision table, read only. No YAML or JSON is shown here. */
export function DecisionTable({
  definition,
  version,
  matched,
}: {
  definition: RuleSetDefinition;
  version: number;
  matched: ReadonlySet<string>;
}) {
  const { t, locale } = useI18n();
  return (
    // A focusable, labelled region: a wide table scrolls sideways and stays reachable from the keyboard.
    <div className="dt-scroll" role="region" tabIndex={0} aria-label={t('table.caption', { name: definition.ruleSet, version: String(version) })}>
      <table className="dt">
        <caption className="sr-only">{t('table.caption', { name: definition.ruleSet, version: String(version) })}</caption>
        <TableHead definition={definition} />
        <tbody>
          {definition.rules.map((rule) => {
            const isMatched = matched.has(rule.id);
            return (
              <tr key={rule.id} className={isMatched ? 'dt__row--matched' : undefined}>
                <th scope="row" className="dt__rowhead">
                  <span className="row-id mono">{rule.id}</span>
                  {isMatched ? (
                    <span className="match-mark">
                      <span aria-hidden="true">✓</span> {t('table.matched')}
                    </span>
                  ) : null}
                </th>
                {definition.inputs.map((input) => (
                  <td key={input.name}>
                    <CellValue reading={readCondition(rule.when[input.name], locale)} />
                  </td>
                ))}
                {definition.outputs.map((output, index) => (
                  <td key={output.name} className={index === 0 ? 'dt__first-then' : undefined}>
                    <CellValue reading={readOutput(rule.then[output.name], locale)} />
                  </td>
                ))}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
