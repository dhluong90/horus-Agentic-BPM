<script setup>
import { withBase } from 'vitepress';
import HxIcons from './HxIcons.vue';
import HxFooter from './HxFooter.vue';

const repo = 'https://github.com/dhluong90/horus-Agentic-BPM';

const pillars = [
  { icon: 'i-chip', title: 'No cluster to run', lead: 'Add a Spring Boot starter. State lives in your PostgreSQL, in your transactions.',
    ticks: ['A decision log entry rolls back with your business write.', 'Run it inside your service, or reach it over its REST API.'] },
  { icon: 'i-shield', title: 'Change control the engine enforces', lead: 'The person who requests a change cannot approve it. Whatever their roles.',
    ticks: ['Versions take effect on a date you choose, after approval.', 'Every decision is written to an append-only log.'] },
  { icon: 'i-agent', ai: true, title: 'AI agents on a leash', lead: 'An agent step passes a JSON Schema and your rule set before the process moves on.',
    ticks: ['A rule that fires sends the step to a human reviewer, or fails it.', 'The engine never calls a model itself.'] },
  { icon: 'i-table', title: 'Rules your business edits', lead: 'Author, test, approve and publish decision tables in the cockpit. No code release.', ticks: [] }
];

// Real captures of the 1.0 cockpit. w1 x h1 is the 1x size; w2 the 2x width.
const shots = [
  { id: 'process', h1: 430, w2: 1400, eyebrow: 'BPMN diagram', title: 'Every executable element, on the diagram.',
    text: 'Sub-processes, boundary events and compensation, drawn from the BPMN file you import.',
    alt: 'The Loan payout process drawn as a BPMN diagram in the cockpit, with a sub-process, service tasks and an undo path.' },
  { id: 'timeline', h1: 438, w2: 1400, eyebrow: 'Instance timeline', title: 'See each step, and what was undone.',
    text: 'A finished instance shows its steps, and which ones compensation undid.',
    alt: 'A process instance in the cockpit. Two service tasks are marked Undone, and their undo steps are marked Used to undo.' },
  { id: 'rule-editor', h1: 438, w2: 1400, eyebrow: 'Rule Engine', title: 'Edit a decision table in plain words.',
    text: 'Each rule reads back as a sentence. Publish without a code release.',
    alt: 'The decision table editor. Rows R1 to R4 each show a sentence such as "Rule R3 applies when tier is Gold".' },
  { id: 'try-it', h1: 437, w2: 1400, eyebrow: 'Try it and simulate', title: 'Try the live rules. Simulate a draft.',
    text: 'Try it runs the version in effect on sample values. Simulate compares a draft with the live version before anyone approves.',
    alt: 'A decision table with row R3 highlighted as matched for a Gold tier input.' },
  { id: 'approver-review', h1: 438, w2: 1380, eyebrow: 'Maker-checker', title: 'The approver reads the change in plain words.',
    text: 'The current version sits beside the proposed one. The fee changes from 30,000 VND to 25,000 VND.',
    alt: 'Version 4 of a fee table beside the version in force, with the changed fee marked, and the Approve and publish and Reject buttons.' },
  { id: 'self-approval', h1: 438, w2: 1400, eyebrow: 'Four eyes', title: 'You cannot approve your own change.',
    text: 'The requester sees Approve disabled. If a call gets through, the engine refuses with a 403.',
    alt: 'The same review screen for the requester. The Approve and publish button is disabled with the note "You requested this. Another approver decides."' },
  { id: 'decision-log', h1: 438, w2: 1400, eyebrow: 'Decision log', title: 'Every decision, explained and kept.',
    text: 'The log is append-only. There is no edit path.',
    alt: 'A logged card-fee decision. The text says rule R3 of version 3 matched because tier is GOLD, with the result of a 30,000 VND fee.' }
];
const src = (id, w) => withBase(`/images/home/${id}-${w}.webp`);
const srcset = (s) => `${src(s.id, 700)} 700w, ${src(s.id, 1400)} ${s.w2}w`;

const compare = [
  ['How it runs', 'A library in your Spring Boot service', 'A remote cluster, with a search index'],
  ['Where state lives', 'Your PostgreSQL, in your transactions', "The engine's own store, plus exported history"],
  ['Change approval', 'Second approver enforced by the engine', 'Built by you around the modeller'],
  ['Decision log', 'Append-only, same transaction as your write', 'Decision instances in Operate and Optimize'],
  ['AI agent output', 'Schema and rule check before the process continues', 'Guardrails are built by you around the agent'],
  ['Import', 'Camunda Modeler and bpmn.io files', 'Native'],
  ['Licence', 'Source-available, free for noncommercial use', 'Source-available, non-production only; production needs an Enterprise agreement']
];
const camundaBetter = [
  ['Track record.', 'Camunda has years of production use and a large community. We have no published production references yet.'],
  ['Scale.', 'Camunda partitions work across a cluster. We scale by adding replicas of your service on one PostgreSQL, and we have not published benchmark results.'],
  ['Languages.', 'Camunda has job workers in several languages. We are Java and Spring Boot, with a REST API for the rest.'],
  ['Ready-made pieces.', 'Camunda ships connectors and a graphical modeller. We have neither: you model in Camunda Modeler or bpmn.io, and write the adapters to your own systems.'],
  ['Licence.', 'We are source-available, not open source. Commercial use needs a licence from us.']
];
const features = [
  ['i-flow', 'Every executable BPMN 2.0 element', 'Gateways, events, compensation and sub-processes, imported and run.'],
  ['i-table', 'DMN decision tables', 'Import and export DMN 1.2 to 1.5 tables.'],
  ['i-import', 'Import from your modeller', 'Bring in Camunda Modeler or bpmn.io files as they are.'],
  ['i-users', 'Maker-checker', 'Requesters cannot approve their own changes. The engine refuses.'],
  ['i-log', 'Append-only audit', 'Decision log, tenant switches and approvals, with no edit path.'],
  ['i-agent', 'Guarded AI agents', 'Schema check, then your rules, then a human if needed.'],
  ['i-layers', 'Multi-tenancy', 'Tenant is the first key of every index.'],
  ['i-clock', 'Task queues and SLAs', 'Business-hours SLAs, escalation and compensation of earlier steps.']
];
</script>

<template>
  <div class="hx">
    <HxIcons />
    <main id="main">
      <section class="hero">
        <div class="wrap wide">
          <div class="grid">
            <div>
              <h1>Embeddable BPMN, DMN and rules engine <em>for Java.</em></h1>
              <p class="lead">It runs inside your Spring Boot service and PostgreSQL, and enforces a second approver on every rule change.</p>
              <div class="ctas">
                <a class="btn btn-primary" :href="withBase('/Getting-Started')">Get started <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12h14M13 6l6 6-6 6" /></svg></a>
                <a class="btn btn-secondary" :href="repo"><svg width="20" height="20" aria-hidden="true"><use href="#i-gh" /></svg> View on GitHub</a>
              </div>
              <p class="small">Java 21 or 25. Spring Boot. PostgreSQL. No cluster.</p>
            </div>
            <div class="canvas-card" role="img" aria-label="A rule change process: draft, request approval, second approver check, publish on the effective date. A rejection returns to draft.">
              <div class="canvas-top"><span>Processes /</span><b>Rule change</b><span class="live"><span class="dotlive"></span>Running</span></div>
              <svg viewBox="0 0 596 330" aria-hidden="true">
                <defs>
                  <marker id="ah" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0 1l9 4-9 4z" fill="currentColor" style="color:var(--edge)" /></marker>
                  <filter id="gl" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="9" /></filter>
                  <filter id="sh" x="-20%" y="-20%" width="140%" height="150%"><feDropShadow dx="0" dy="3" stdDeviation="4" flood-color="#0E2F29" flood-opacity=".14" /></filter>
                </defs>
                <g class="ed" marker-end="url(#ah)">
                  <path d="M40 160H62" /><path d="M162 160H192" /><path d="M292 160H306" /><path d="M364 160H428" /><path d="M528 160H554" />
                  <path d="M336 188V270H112V190" stroke-dasharray="5 4" />
                </g>
                <circle cx="336" cy="160" r="38" style="fill:var(--glow)" opacity=".38" filter="url(#gl)" />
                <g class="nd">
                  <circle cx="26" cy="160" r="14" filter="url(#sh)" />
                  <rect x="62" y="130" width="100" height="60" rx="10" filter="url(#sh)" />
                  <rect x="192" y="130" width="100" height="60" rx="10" filter="url(#sh)" />
                  <rect x="428" y="130" width="100" height="60" rx="10" filter="url(#sh)" />
                  <path d="M336 130l30 30-30 30-30-30z" style="stroke:var(--token);stroke-width:2.4" filter="url(#sh)" />
                  <circle cx="568" cy="160" r="14" style="stroke-width:3.5" filter="url(#sh)" />
                </g>
                <path d="M329 160l5 5 9-10" fill="none" style="stroke:var(--token)" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
                <g class="tx" font-size="13.5" text-anchor="middle" font-weight="500">
                  <text x="112" y="157">Draft the</text><text x="112" y="175">change</text>
                  <text x="242" y="157">Request</text><text x="242" y="175">approval</text>
                  <text x="478" y="157">Publish on</text><text x="478" y="175">effective date</text>
                </g>
                <g class="tx2" font-size="12" text-anchor="middle"><text x="396" y="150">Approved</text><text x="224" y="262">Rejected: back to draft</text></g>
                <g>
                  <rect x="228" y="48" width="216" height="34" rx="17" style="fill:var(--accent-soft);stroke:var(--primary-fill)" />
                  <text x="336" y="70" text-anchor="middle" font-size="13.5" font-weight="600" style="fill:var(--teal)">Requester cannot approve</text>
                  <path d="M336 82V120" style="stroke:var(--primary-fill)" stroke-width="1.5" stroke-dasharray="3 3" />
                </g>
              </svg>
            </div>
          </div>
        </div>
      </section>

      <section>
        <div class="wrap wide">
          <div class="sec-head center"><p class="eyebrow">Why it fits</p><h2>Built for governed change</h2></div>
          <div class="pillars">
            <article v-for="p in pillars" :key="p.title" class="pillar">
              <div class="ic" :class="{ ai: p.ai }"><svg width="24" height="24" aria-hidden="true"><use :href="'#' + p.icon" /></svg></div>
              <h3>{{ p.title }}</h3>
              <p>{{ p.lead }}</p>
              <ul v-if="p.ticks.length"><li v-for="t in p.ticks" :key="t">{{ t }}</li></ul>
            </article>
          </div>
        </div>
      </section>

      <section id="see" class="tint">
        <div class="wrap wide">
          <div class="sec-head center"><p class="eyebrow">See it run</p><h2>The cockpit, embedded in your service.</h2></div>
          <div class="show">
            <div v-for="(s, i) in shots" :key="s.id" class="row" :class="{ flip: i % 2 === 1 }">
              <figure class="shot">
                <img :src="src(s.id, 700)" :srcset="srcset(s)" sizes="(max-width: 1100px) 100vw, 700px" width="700" :height="s.h1" :alt="s.alt" loading="lazy" decoding="async" />
              </figure>
              <div><p class="eyebrow">{{ s.eyebrow }}</p><h3>{{ s.title }}</h3><p>{{ s.text }}</p></div>
            </div>
          </div>
        </div>
      </section>

      <section id="compare">
        <div class="wrap">
          <div class="sec-head"><p class="eyebrow">Compare</p><h2>Why not just Camunda?</h2><p class="lead">Camunda is excellent. Here is when we fit better.</p></div>
          <p class="lead" style="margin:-24px 0 36px;max-width:70ch">Camunda is the mature, broad choice. If you are building a central orchestration platform for many teams, with a platform team to run it, choose Camunda. We are built for a narrower job: governed change inside one Java service.</p>
          <div class="cmp">
            <table>
              <thead><tr><th scope="col"><span class="vh">Topic</span></th><th scope="col" class="us">HORUS Agentic BPM</th><th scope="col">Camunda 8</th></tr></thead>
              <tbody>
                <tr v-for="r in compare" :key="r[0]">
                  <th scope="row">{{ r[0] }}</th>
                  <td class="us" data-l="HORUS Agentic BPM">{{ r[1] }}</td>
                  <td data-l="Camunda 8">{{ r[2] }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="fit">
            <div class="card">
              <h3>Where Camunda is the better fit today</h3>
              <ul><li v-for="c in camundaBetter" :key="c[0]"><b>{{ c[0] }}</b> {{ c[1] }}</li></ul>
            </div>
            <div class="card pick">
              <h3>Pick HORUS Agentic BPM if</h3>
              <p>your services are Java on PostgreSQL, business teams change rules under approval, an auditor asks who approved what, and you do not want to run another cluster for workflow.</p>
            </div>
          </div>
        </div>
      </section>

      <section class="tint">
        <div class="wrap wide">
          <div class="sec-head center"><p class="eyebrow">Features</p><h2>What is in the box</h2></div>
          <div class="features">
            <div v-for="f in features" :key="f[1]" class="feat">
              <svg class="ic" aria-hidden="true"><use :href="'#' + f[0]" /></svg><h3>{{ f[1] }}</h3><p>{{ f[2] }}</p>
            </div>
          </div>
        </div>
      </section>

      <section id="quick-start">
        <div class="wrap">
          <div class="sec-head center"><p class="eyebrow">Quick start</p><h2>From clone to a governed decision.</h2><p class="lead">Under thirty minutes. Needs Docker with Compose v2, git, curl and jq.</p></div>
          <div class="steps">
            <div class="step"><div class="num" aria-hidden="true">1</div><div><h3>Clone and create local secrets</h3>
<pre tabindex="0" aria-label="Commands, step 1"><code>git clone https://github.com/dhluong90/horus-Agentic-BPM.git
cd horus-Agentic-BPM
cp .env.example .env
sed -i.bak 's/^SPRING_PROFILES_ACTIVE=.*/SPRING_PROFILES_ACTIVE=dev/' .env &amp;&amp; rm .env.bak
deploy/create-secrets.sh</code></pre></div></div>
            <div class="step"><div class="num" aria-hidden="true">2</div><div><h3>Start the example host and PostgreSQL</h3>
<pre tabindex="0" aria-label="Commands, step 2"><code>docker compose up --build --wait</code></pre><p>The first build takes 2 to 5 minutes. Then <code class="i">curl -s 127.0.0.1:8080/actuator/health</code> answers <code class="i">UP</code>.</p></div></div>
            <div class="step"><div class="num" aria-hidden="true">3</div><div><h3>Submit a rule change, and watch the engine refuse a self-approval</h3>
<pre tabindex="0" aria-label="Commands, step 3"><code>H=127.0.0.1:8080/api/bpm/rules/v1
P=$(cat secrets/demo_password)
curl -s -u hoa:$P -X POST $H/rule-sets -H 'Content-Type: application/yaml' \
  -H 'Idempotency-Key: quickstart-1' \
  --data-binary @examples/rule-engine-only/src/main/resources/rules/card-fee.yaml
curl -s -u hoa:$P -X POST $H/rule-sets/card-fee/versions/1:request-publish
HASH=$(curl -s -u hoa:$P $H/rule-sets/card-fee/versions/1 | jq -r .canonicalHash)
curl -s -u hoa:$P -X POST $H/rule-sets/card-fee/versions/1:approve \
  -H 'If-Match: "1"' -H 'Content-Type: application/json' \
  -d '{"expectedCanonicalHash":"'$HASH'"}'</code></pre>
              <p>The approval is refused, because hoa made the request: <code class="i">403 SELF_APPROVAL_FORBIDDEN</code>. Sign in to the cockpit at <code class="i">http://127.0.0.1:8080/bpm/cockpit</code> as <code class="i">minh</code> (password in <code class="i">secrets/demo_password</code>) and approve it there.</p></div></div>
          </div>
          <p class="then"><a :href="withBase('/Getting-Started')">Read the full Getting Started guide</a></p>
          <p class="warn">Local evaluation only. Demo users and plain HTTP are not for production.</p>
        </div>
      </section>

      <section class="tint">
        <div class="wrap">
          <p class="arch-s">The engines are libraries inside your Spring Boot service: they keep all state in your PostgreSQL, run in your transactions, and the cockpit is static files your service serves.</p>
          <figure class="arch" style="margin:0 auto" role="img" aria-label="Your Spring Boot service contains the Rule Engine, Process Engine and Cockpit files. It talks to your PostgreSQL in one transaction. Your identity provider sits outside and signs users in. Your adapters, such as core banking and an AI model, are called after the commit.">
            <svg viewBox="0 0 900 340" aria-hidden="true">
              <defs><marker id="a2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0 1l9 4-9 4z" fill="currentColor" style="color:var(--edge)" /></marker></defs>
              <g text-anchor="middle">
                <rect class="nd" x="150" y="30" width="400" height="230" rx="16" />
                <text class="tx" x="350" y="64" font-size="16" font-weight="650">Your Spring Boot service</text>
                <g font-size="14" font-weight="500">
                  <rect class="chip" x="176" y="96" width="106" height="48" rx="10" /><text class="chiptx" x="229" y="125">Rule Engine</text>
                  <rect class="chip" x="297" y="96" width="106" height="48" rx="10" /><text class="chiptx" x="350" y="125">Process Engine</text>
                  <rect class="chip" x="418" y="96" width="106" height="48" rx="10" /><text class="chiptx" x="471" y="125">Cockpit files</text>
                </g>
                <text class="tx2" x="350" y="206" font-size="14">Libraries, not a server. No cluster, no broker.</text>
                <rect class="nd" x="690" y="80" width="190" height="130" rx="16" />
                <text class="tx" x="785" y="134" font-size="16" font-weight="650">Your PostgreSQL</text><text class="tx2" x="785" y="162" font-size="14">rle_ and pre_ tables</text>
                <path d="M550 145H688" style="stroke:var(--primary-fill)" stroke-width="2.5" fill="none" marker-start="url(#a2)" marker-end="url(#a2)" />
                <text class="chiptx" x="620" y="132" font-size="14" font-weight="600">your transaction</text>
                <path class="ed" d="M350 322V262" marker-end="url(#a2)" />
                <text class="tx3" x="350" y="334" font-size="13.5">Your identity provider: sign-in and tenant</text>
                <path class="ed" d="M560 240H650" stroke-dasharray="5 4" marker-end="url(#a2)" />
                <text class="tx3" x="780" y="236" font-size="13.5">Your adapters: core banking,</text><text class="tx3" x="780" y="254" font-size="13.5">AI model, after commit</text>
              </g>
            </svg>
          </figure>
        </div>
      </section>

      <section class="cta">
        <div class="wrap">
          <h2>Run it on your laptop in thirty minutes.</h2>
          <div class="ctas">
            <a class="btn btn-primary" :href="withBase('/Getting-Started')">Get started</a>
            <a class="btn btn-secondary" :href="repo"><svg width="20" height="20" aria-hidden="true"><use href="#i-gh" /></svg> View on GitHub</a>
          </div>
        </div>
      </section>
    </main>
    <HxFooter />
  </div>
</template>
