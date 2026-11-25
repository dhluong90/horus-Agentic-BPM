<script setup>
import { withBase } from 'vitepress';
const repo = 'https://github.com/dhluong90/horus-Agentic-BPM';
</script>

<template>
  <footer>
    <div class="wrap wide">
      <div class="fgrid">
        <div class="about">
          <a class="brand" :href="withBase('/')"><span class="logo"><svg width="28" height="28" aria-hidden="true" style="color:#fff"><use href="#i-hmark" /></svg></span><span class="t">HORUS Agentic BPM</span></a>
          <p>Embeddable BPMN, DMN and rules engine for Java.</p>
        </div>
        <nav aria-label="Docs">
          <h2 class="fh">Docs</h2>
          <ul>
            <li><a :href="withBase('/Getting-Started')">Getting Started</a></li>
            <li><a :href="withBase('/Concepts')">Concepts</a></li>
            <li><a :href="withBase('/Architecture')">Architecture</a></li>
            <li><a :href="withBase('/Guides')">Developer guides</a></li>
            <li><a :href="withBase('/Compare-and-Choose')">Compare and choose</a></li>
          </ul>
        </nav>
        <nav aria-label="Project">
          <h2 class="fh">Project</h2>
          <ul>
            <li><a :href="withBase('/Release-Notes')">Release notes</a></li>
            <li><a :href="withBase('/Roadmap')">Roadmap</a></li>
            <li><a :href="withBase('/Contributing')">Contributing</a></li>
          </ul>
        </nav>
        <nav aria-label="Security">
          <h2 class="fh">Security</h2>
          <ul>
            <li><a :href="repo + '/blob/main/SECURITY.md'">Security policy</a></li>
            <li><a :href="repo + '/security/advisories/new'">Report a vulnerability</a></li>
          </ul>
        </nav>
        <nav aria-label="Code">
          <h2 class="fh">Code</h2>
          <ul>
            <li><a :href="repo">GitHub</a></li>
            <li><a :href="repo + '/issues'">Issues</a></li>
          </ul>
        </nav>
      </div>
      <div class="legal">
        <span>Source-available, free for noncommercial use. For commercial use, <a :href="repo + '/blob/main/COMMERCIAL-LICENSE.md'">contact us</a>. See the <a :href="repo + '/blob/main/LICENSE'">licence</a>.</span>
        <span>Copyright 2026 HORUS.</span>
      </div>
    </div>
  </footer>
</template>
