// Sign-in page behaviour, served from the host's own origin (script-src 'self'). The page works without it:
// the server renders the language and the form posts natively. It adds the cockpit's remembered language and
// theme, a theme switch, and a field message in the page's language when a field is left empty.
(function () {
  'use strict';
  var LOCALE_KEY = 'horus.cockpit.locale';
  var THEME_KEY = 'horus.cockpit.theme';

  function read(key) {
    try { return window.localStorage.getItem(key); } catch (e) { return null; }
  }
  function write(key, value) {
    try { window.localStorage.setItem(key, value); } catch (e) { /* storage disabled: the choice lasts for this page */ }
  }

  // The language the user chose in the cockpit wins, unless this address already names one.
  var params = new URLSearchParams(window.location.search);
  var stored = read(LOCALE_KEY);
  var lang = document.documentElement.lang;
  if (!params.has('lang') && (stored === 'en' || stored === 'vi') && stored !== lang) {
    params.set('lang', stored);
    window.location.replace(window.location.pathname + '?' + params.toString());
    return;
  }

  var theme = read(THEME_KEY);
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-lang]').forEach(function (link) {
      link.addEventListener('click', function () { write(LOCALE_KEY, link.getAttribute('data-lang')); });
    });

    var toggle = document.getElementById('theme-toggle');
    if (toggle) {
      var label = function () {
        var dark = currentTheme() === 'dark';
        var text = dark ? toggle.getAttribute('data-to-light') : toggle.getAttribute('data-to-dark');
        toggle.setAttribute('aria-label', text);
        toggle.setAttribute('title', text);
      };
      toggle.hidden = false;
      label();
      toggle.addEventListener('click', function () {
        var next = currentTheme() === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.theme = next;
        write(THEME_KEY, next);
        label();
      });
    }

    // A demo row fills the username and moves on to the password.
    document.querySelectorAll('.demo__user').forEach(function (row) {
      row.addEventListener('click', function () {
        var username = document.getElementById('username');
        username.value = row.getAttribute('data-username');
        document.querySelectorAll('.demo__user').forEach(function (other) { other.classList.toggle('is-picked', other === row); });
        document.getElementById('password').focus();
      });
    });

    var form = document.getElementById('sign-in-form');
    if (form) {
      form.addEventListener('submit', function (event) {
        var first = null;
        ['username', 'password'].forEach(function (id) {
          var input = document.getElementById(id);
          var message = document.getElementById(id + '-error');
          var empty = input.value.trim() === '';
          input.setAttribute('aria-invalid', empty ? 'true' : 'false');
          if (empty) {
            message.textContent = input.getAttribute('data-required');
            message.hidden = false;
            input.setAttribute('aria-describedby', id + '-error');
            if (!first) first = input;
          } else {
            message.hidden = true;
            input.removeAttribute('aria-describedby');
          }
        });
        if (first) {
          event.preventDefault();
          first.focus();
        }
      });
    }
  });

  function currentTheme() {
    var set = document.documentElement.dataset.theme;
    if (set === 'light' || set === 'dark') return set;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
})();
