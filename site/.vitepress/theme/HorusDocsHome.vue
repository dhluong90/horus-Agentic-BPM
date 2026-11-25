<script setup>
import { withBase } from 'vitepress';
import HxIcons from './HxIcons.vue';
import HxFooter from './HxFooter.vue';

const popular = [
  ['Getting Started', 'Getting-Started'],
  ['Import a BPMN file', 'Process-Engine-Importing-BPMN'],
  ['Author a decision table', 'Rule-Engine-Authoring-Decision-Tables'],
  ['Guarded agent steps', 'Guarded-Agent-Steps'],
  ['Compare and choose', 'Compare-and-Choose']
];
const cards = [
  ['i-rocket', 'Get started', 'Run the example host and your first decision.', 'Getting-Started'],
  ['i-table', 'Rules', 'Author, test, approve and publish decision tables.', 'Rule-Engine-Authoring-Decision-Tables'],
  ['i-flow', 'Processes', 'Import BPMN, run it, handle tasks and errors.', 'Process-Engine-Overview'],
  ['i-agent', 'AI agents', 'Guard agent steps with a schema and your rules.', 'Guarded-Agent-Steps'],
  ['i-compass', 'Operate', 'Cockpit, tenants, security and the decision log.', 'Cockpit'],
  ['i-api', 'Reference', 'APIs, configuration, errors and architecture.', 'Guide-REST-API-and-OpenAPI']
];
const ladder = [
  { title: 'Run the sample BPMN and DMN in the cockpit.',
    text: 'For people evaluating it, with no code of their own to write. Start the example host, open the cockpit, import a sample, approve it as a second person, run it.',
    links: [['Getting Started', 'Getting-Started'], ['Import a BPMN file', 'Process-Engine-Importing-BPMN']] },
  { title: 'Embed it in your Spring Boot service.',
    text: 'For developers who want it inside an existing service.',
    links: [['Getting Started: your own service', 'Getting-Started-Your-Own-Service'], ['Embedding in Spring Boot', 'Guide-Embedding-in-Spring-Boot']] },
  { title: 'Add human tasks with maker-checker.',
    text: 'For developers who build approval screens and must enforce a second approver.',
    links: [['Task handlers and forms', 'Guide-Task-Handlers-and-Forms'], ['Versioning and publishing', 'Rule-Engine-Versioning-and-Publishing']] },
  { title: 'Let business users edit rules in the cockpit.',
    text: 'For rule authors and analysts who change decision tables without a release.',
    links: [['Author a decision table', 'Rule-Engine-Authoring-Decision-Tables']] },
  { title: 'Add a guarded AI agent step.',
    text: 'For teams adding AI to a process, and the compliance reviewers who sign it off.',
    links: [['Guarded agent steps', 'Guarded-Agent-Steps'], ['Autonomy ladder and evals', 'Agent-Autonomy-Ladder-and-Eval-Guide']] }
];
// The search box of the theme listens for Ctrl+K on the window; this button asks it to open.
function openSearch() {
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true }));
}
</script>

<template>
  <div class="hx">
    <HxIcons />
    <main id="main">
      <section class="dhero">
        <div class="wrap">
          <h1>Documentation</h1>
          <p class="lead">Guides, reference and design for HORUS Agentic BPM.</p>
          <button type="button" class="bigsearch" @click="openSearch"><svg width="22" height="22" aria-hidden="true"><use href="#i-search" /></svg>Search the docs<kbd>Ctrl K</kbd></button>
          <p class="popular"><b>Popular:</b><a v-for="p in popular" :key="p[1]" :href="withBase('/' + p[1])">{{ p[0] }}</a></p>
        </div>
      </section>
      <div class="wrap">
        <div class="cards">
          <a v-for="c in cards" :key="c[1]" class="dcard" :href="withBase('/' + c[3])">
            <svg class="ic" aria-hidden="true"><use :href="'#' + c[0]" /></svg><h2 class="ct">{{ c[1] }}</h2><p>{{ c[2] }}</p><span class="go">Open <span aria-hidden="true">&rarr;</span></span>
          </a>
        </div>
        <h2 class="dh2">Where to start</h2>
        <ol class="ladder">
          <li v-for="(l, i) in ladder" :key="l.title">
            <div class="num" aria-hidden="true">{{ i + 1 }}</div>
            <div>
              <h3>{{ l.title }}</h3>
              <p>{{ l.text }}</p>
              <p class="links"><a v-for="k in l.links" :key="k[1]" :href="withBase('/' + k[1])">{{ k[0] }}</a></p>
            </div>
          </li>
        </ol>
      </div>
    </main>
    <HxFooter />
  </div>
</template>
