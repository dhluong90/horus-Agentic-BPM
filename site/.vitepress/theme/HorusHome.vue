<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue';
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

// Real captures of the cockpit. w1 x h1 is the 1x size; w2 the 2x width.
const shots = [
  { id: 'process', h1: 377, w2: 1030, eyebrow: 'BPMN diagram', title: 'See every running step on the diagram.',
    text: 'Boundary events and compensation, from the BPMN you import, drawn as its diagram has them.',
    alt: 'The Loan payout process drawn as a BPMN diagram in the cockpit, with service tasks, a boundary event and an undo path.' },
  { id: 'timeline', wide: true, h1: 622, w2: 1400, eyebrow: 'Instance timeline', title: 'See each step, and what was undone.',
    text: 'A finished instance shows its steps, and which ones compensation undid.',
    alt: 'A process instance in the cockpit. Two service tasks are marked Undone, and their undo steps are marked Used to undo.' },
  { id: 'rule-editor', wide: true, h1: 641, w2: 1400, eyebrow: 'Rule Engine', title: 'Edit a decision table in plain words.',
    text: 'Each rule reads back as a sentence. Publish without a code release.',
    alt: 'The decision table editor. Rows R1 to R5 each show a sentence such as "Rule R3 applies when tier is Gold".' },
  { id: 'try-it', w1: 360, h1: 528, w2: 720, tall: true, eyebrow: 'Try it and simulate', title: 'Try the live rules. Simulate a draft.',
    text: 'Try it runs the version in effect on sample values. Simulate compares a draft with the live version before anyone approves.',
    alt: 'A decision table with row R3 highlighted as matched for a Gold tier input.' },
  { id: 'approver-review', wide: true, h1: 608, w2: 1400, eyebrow: 'Maker-checker', title: 'The approver reads the change in plain words.',
    text: 'The current version sits beside the proposed one. The fee changes from 30,000 VND to 25,000 VND.',
    alt: 'Version 4 of a fee table beside the version in force, with the changed fee marked, and the Approve and publish and Reject buttons.' },
  { id: 'self-approval', wide: true, h1: 587, w2: 1400, eyebrow: 'Four eyes', title: 'You cannot approve your own change.',
    text: 'The requester sees Approve disabled. If a call gets through, the engine refuses with a 403.',
    alt: 'The same review screen for the requester. The Approve and publish button is disabled with the note "You requested this. Another approver decides."' },
  { id: 'decision-log', h1: 318, w2: 1400, eyebrow: 'Decision log', title: 'Every decision, explained and recorded.',
    text: 'The log is append-only. There is no edit path.',
    alt: 'A logged card-fee decision. The text says rule R3 of version 3 matched because tier is GOLD, with the result of a 30,000 VND fee.' }
];
const src = (id, w) => withBase(`/images/home/${id}-${w}.webp`);
// The width and height of the file `src` points at, so the browser reserves the real ratio before (or without) the image.
const dims = (s) => ({ w: s.wide ? 1400 : s.w1 || 700, h: s.h1 });
const srcset = (s) => `${src(s.id, s.w1 || 700)} ${s.w1 || 700}w, ${src(s.id, s.w2)} ${s.w2}w`;

// The hero crop runs from the start event to the risk route. Every WebP is named by its real width; the 1579 px one is the 2x capture.
const heroSrc = (w) => withBase(`/images/home/loan-hero-crop-${w}w.webp`);
const heroSet = [790, 1184, 1579].map((w) => `${heroSrc(w)} ${w}w`).join(', ');
const heroAlt = 'Part of a loan origination process in the cockpit: an application is submitted, validated, screened by an AI agent for KYC and AML, graded for credit risk, and a risk route sends the case on to a decline, a referral or automatic approval.';

// Two short screen recordings, cropped to the used area. The width and height attributes reserve the box before any byte loads.
const videos = {
  loan: { id: 'loan-flow', w: 1280, h: 740, eyebrow: 'Process', title: 'A loan application, end to end',
    text: 'An AI agent screens the application, a person reviews it in the cockpit, and it is routed by risk. In this demo the proposal and approval steps are sent through the API.',
    label: 'Screen recording of a loan application in the cockpit: an AI agent screens it, a person reviews the agent answer, and the case follows the diagram. The proposal and approval steps are sent through the API in this demo.' },
  rule: { id: 'rule-flow-v2', w: 1440, h: 854, eyebrow: 'Maker-checker', title: 'Change a rule, simulate, approve',
    text: 'A maker edits a rule and simulates it. A second person reviews the change and approves it.',
    label: 'Screen recording of a rule change: edit a decision table, simulate it against the live version, then a second user approves.' }
};
const vsrc = (v, ext) => withBase(`/images/home/${v.id}.${ext}`);
const vposter = (v) => withBase(`/images/home/${v.id}-poster.webp`);

// With prefers-reduced-motion the videos do not play by themselves: the poster stays and the browser's play control appears.
const clips = ref([]);
onMounted(() => {
  if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  clips.value.forEach((v) => { if (v) { v.removeAttribute('autoplay'); v.pause(); v.currentTime = 0; v.controls = true; } });
});

const features = [
  ['i-flow', 'Run BPMN 2.0 elements you can list', 'Gateways, events, compensation and sub-processes run on the engine.'],
  ['i-table', 'DMN decision tables', 'Import and export DMN 1.2 to 1.5 tables.'],
  ['i-import', 'Import BPMN 2.0, with clear refusals', 'Bring in files that use the supported elements. Anything else is refused with its reason.'],
  ['i-users', 'Maker-checker', 'Requesters cannot approve their own changes. The engine refuses.'],
  ['i-log', 'Append-only audit', 'Decision log, tenant switches and approvals, with no edit path.'],
  ['i-agent', 'Guarded AI agents', 'Schema check, then your rules, then a human if needed.'],
  ['i-layers', 'Multi-tenancy', 'Tenant is the first key of every index.'],
  ['i-clock', 'Task queues and SLAs', 'Business-hours SLAs, escalation and compensation of earlier steps.']
];

// A screenshot frame is a picture, not a control. It is keyboard-focusable only while it really scrolls sideways
// (a phone-width viewport), so that a keyboard user can pan it; otherwise it has no tab stop and no focus ring.
const frames = ref([]);
const scrolls = ref({});
let watcher;
// The hero diagram is shown at a readable size, so at phone width it scrolls sideways inside its frame, with a cue until the first swipe.
const heroFrame = ref(null);
const heroScrolls = ref(false);
const heroMoved = ref(false);
const measure = () => {
  frames.value.forEach((el) => { if (el) scrolls.value[el.dataset.shot] = el.scrollWidth > el.clientWidth + 1; });
  if (heroFrame.value) heroScrolls.value = heroFrame.value.scrollWidth > heroFrame.value.clientWidth + 1;
};
onMounted(() => { measure(); watcher = new ResizeObserver(measure); frames.value.forEach((el) => el && watcher.observe(el)); if (heroFrame.value) watcher.observe(heroFrame.value); });
onBeforeUnmount(() => watcher && watcher.disconnect());
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
            <div class="hero-wrap">
              <figure ref="heroFrame" class="canvas-card hero-fig" :tabindex="heroScrolls ? 0 : undefined" :role="heroScrolls ? 'group' : undefined" :aria-label="heroScrolls ? 'Loan process diagram. Scroll sideways to see all of it.' : undefined" @scroll.passive="heroMoved = true">
                <img :src="heroSrc(1579)" :srcset="heroSet" sizes="(max-width: 640px) 790px, (max-width: 1279px) min(880px, calc(100vw - 64px)), 812px" width="1579" height="1365" :alt="heroAlt" fetchpriority="high" decoding="async" />
              </figure>
              <span v-if="heroScrolls && !heroMoved" class="hero-cue" aria-hidden="true">Swipe to see more →</span>
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
          <figure class="vid">
              <video :ref="(el) => el && !clips.includes(el) && clips.push(el)" autoplay muted loop playsinline preload="metadata" :poster="vposter(videos.loan)" :width="videos.loan.w" :height="videos.loan.h" :style="{ aspectRatio: `${videos.loan.w} / ${videos.loan.h}` }" :aria-label="videos.loan.label">
                <source :src="vsrc(videos.loan, 'webm')" type="video/webm" />
                <source :src="vsrc(videos.loan, 'mp4')" type="video/mp4" />
              </video>
              <figcaption><span class="eyebrow">{{ videos.loan.eyebrow }}</span><strong>{{ videos.loan.title }}</strong><span>{{ videos.loan.text }}</span></figcaption>
            </figure>
          <div class="show">
            <template v-for="(s, i) in shots" :key="s.id">
            <template v-if="s.id === 'approver-review'">
            <figure class="vid">
              <video :ref="(el) => el && !clips.includes(el) && clips.push(el)" autoplay muted loop playsinline preload="metadata" :poster="vposter(videos.rule)" :width="videos.rule.w" :height="videos.rule.h" :style="{ aspectRatio: `${videos.rule.w} / ${videos.rule.h}` }" :aria-label="videos.rule.label">
                <source :src="vsrc(videos.rule, 'webm')" type="video/webm" />
                <source :src="vsrc(videos.rule, 'mp4')" type="video/mp4" />
              </video>
              <figcaption><span class="eyebrow">{{ videos.rule.eyebrow }}</span><strong>{{ videos.rule.title }}</strong><span>{{ videos.rule.text }}</span></figcaption>
            </figure>
            </template>
            <div class="row" :class="{ flip: i % 2 === 1 && !s.wide, tall: s.tall, wide: s.wide }">
              <figure class="shot" ref="frames" :data-shot="s.id" :tabindex="scrolls[s.id] ? 0 : undefined" :role="scrolls[s.id] ? 'group' : undefined" :aria-label="scrolls[s.id] ? `Screenshot: ${s.eyebrow}. Scroll sideways to see all of it.` : undefined">
                <img :src="src(s.id, s.wide ? 1400 : s.w1 || 700)" :srcset="srcset(s)" :sizes="s.tall ? '360px' : s.wide ? '(max-width: 1240px) 100vw, 1200px' : '(max-width: 1100px) 100vw, 700px'" :width="s.wide ? 1400 : s.w1 || 700" :height="s.h1" :alt="s.alt" :style="{ aspectRatio: `${dims(s).w} / ${dims(s).h}` }" decoding="async" />
              </figure>
              <div><p class="eyebrow">{{ s.eyebrow }}</p><h3>{{ s.title }}</h3><p>{{ s.text }}</p></div>
            </div>
            </template>
          </div>
        </div>
      </section>

      <section>
        <div class="wrap wide">
          <div class="sec-head center"><p class="eyebrow">Features</p><h2>What is in the box</h2></div>
          <div class="features">
            <div v-for="f in features" :key="f[1]" class="feat">
              <svg class="ic" aria-hidden="true"><use :href="'#' + f[0]" /></svg><h3>{{ f[1] }}</h3><p>{{ f[2] }}</p>
            </div>
          </div>
        </div>
      </section>

      <section id="quick-start" class="tint">
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
              <p>The approval is refused, because hoa made the request: <code class="i">403 SELF_APPROVAL_FORBIDDEN</code>. Open the cockpit below and sign in as <code class="i">minh</code> (password in <code class="i">secrets/demo_password</code>). Approve it there.</p>
<pre tabindex="0" aria-label="Cockpit address" style="margin-top:12px"><code>127.0.0.1:8080/bpm/cockpit</code></pre></div></div>
          </div>
          <p class="then"><a :href="withBase('/Getting-Started')">Read the full Getting Started guide</a></p>
          <p class="warn">Local evaluation only. Demo users and plain HTTP are not for production.</p>
        </div>
      </section>

      <section>
        <div class="wrap">
          <p class="arch-s">The engines are libraries in your Spring Boot service. All state lives in your PostgreSQL, in your transactions.</p>
          <figure class="arch" style="margin:0 auto" role="img" aria-label="Your Spring Boot service contains the Rule Engine, Process Engine and Cockpit files. It talks to your PostgreSQL in one transaction. Your identity provider sits outside and signs users in. Your adapters, such as core banking and an AI model, are called after the commit.">
            <svg class="arch-wide" viewBox="0 0 900 340" aria-hidden="true">
              <defs><marker id="a2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0 1l9 4-9 4z" fill="currentColor" style="color:var(--edge)" /></marker></defs>
              <g text-anchor="middle">
                <rect class="nd" x="150" y="30" width="400" height="230" rx="16" />
                <text class="tx" x="350" y="64" font-size="16" font-weight="650">Your Spring Boot service</text>
                <g font-size="14" font-weight="500">
                  <rect class="comp" x="176" y="96" width="106" height="48" rx="10" /><text class="comptx" x="229" y="125">Rule Engine</text>
                  <rect class="comp" x="297" y="96" width="106" height="48" rx="10" /><text class="comptx" x="350" y="125">Process Engine</text>
                  <rect class="comp" x="418" y="96" width="106" height="48" rx="10" /><text class="comptx" x="471" y="125">Cockpit files</text>
                </g>
                <text class="tx2" x="350" y="206" font-size="14">Libraries, not a server. No cluster, no broker.</text>
                <rect class="nd" x="690" y="80" width="190" height="130" rx="16" />
                <text class="tx" x="785" y="134" font-size="16" font-weight="650">Your PostgreSQL</text><text class="tx2" x="785" y="162" font-size="14">rle_ and pre_ tables</text>
                <path d="M550 145H688" style="stroke:var(--primary-fill)" stroke-width="2.5" fill="none" marker-start="url(#a2)" marker-end="url(#a2)" />
                <text class="comptx" x="620" y="132" font-size="14" font-weight="600">your transaction</text>
                <path class="ed" d="M350 322V262" marker-end="url(#a2)" />
                <text class="tx3" x="350" y="334" font-size="13.5">Your identity provider: sign-in and tenant</text>
                <path class="ed" d="M560 240H650" stroke-dasharray="5 4" marker-end="url(#a2)" />
                <text class="tx3" x="780" y="236" font-size="13.5">Your adapters: core banking,</text><text class="tx3" x="780" y="254" font-size="13.5">AI model, after commit</text>
              </g>
            </svg>
            <svg class="arch-narrow" viewBox="0 0 340 540" aria-hidden="true">
              <defs><marker id="a3" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto"><path d="M0 1l9 4-9 4z" fill="currentColor" style="color:var(--edge)" /></marker></defs>
              <g text-anchor="middle" font-size="14">
                <rect class="nd" x="10" y="10" width="320" height="56" rx="12" />
                <text class="tx" x="170" y="34" font-weight="650">Your identity provider</text><text class="tx2" x="170" y="54">sign-in and tenant</text>
                <path class="ed" d="M170 66V94" marker-end="url(#a3)" />
                <rect class="nd" x="10" y="96" width="320" height="210" rx="14" />
                <text class="tx" x="170" y="124" font-weight="650">Your Spring Boot service</text>
                <rect class="comp" x="30" y="140" width="280" height="36" rx="9" /><text class="comptx" x="170" y="163" font-weight="500">Rule Engine</text>
                <rect class="comp" x="30" y="184" width="280" height="36" rx="9" /><text class="comptx" x="170" y="207" font-weight="500">Process Engine</text>
                <rect class="comp" x="30" y="228" width="280" height="36" rx="9" /><text class="comptx" x="170" y="251" font-weight="500">Cockpit files</text>
                <text class="tx2" x="170" y="290">Libraries, not a server. No cluster.</text>
                <path d="M170 306V354" style="stroke:var(--primary-fill)" stroke-width="2.5" fill="none" marker-start="url(#a3)" marker-end="url(#a3)" />
                <text class="comptx" x="186" y="335" text-anchor="start" font-weight="600">your transaction</text>
                <rect class="nd" x="10" y="356" width="320" height="70" rx="14" />
                <text class="tx" x="170" y="386" font-weight="650">Your PostgreSQL</text><text class="tx2" x="170" y="408">rle_ and pre_ tables</text>
                <path class="ed" d="M170 426V468" stroke-dasharray="5 4" marker-end="url(#a3)" />
                <text class="tx3" x="186" y="452" text-anchor="start">after commit</text>
                <rect class="nd" x="10" y="470" width="320" height="60" rx="12" />
                <text class="tx" x="170" y="495" font-weight="650">Your adapters</text><text class="tx2" x="170" y="516">core banking, AI model</text>
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
