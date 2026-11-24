/*
 * MediQueue Web - the screens of the clinic system, drawn in the browser.
 *
 * How it works
 *   1. The page asks the Java server for "the state": everything the signed-in user's screens need.
 *   2. render() draws the current page from that state (like render() in the desktop MediQueue.java).
 *   3. Buttons send a request to the server, for example /api/book. The server checks the rules, saves the
 *      change, and answers with a message and the new state. Then the page is drawn again.
 *
 * Plain JavaScript, no libraries. The part of the address after # (for example #queue) says which page
 * is open, so the browser's Back button works.
 */
'use strict';

(function () {
  const app = document.getElementById('app');
  const toastBox = document.getElementById('toast');

  // =====================================================================
  //  SMALL HELPERS FOR BUILDING THE PAGE
  // =====================================================================

  /**
   * Makes an HTML element: h('button', { class: 'btn', onclick: save }, 'Save').
   * Text is always added as plain text, never as HTML, so a name typed by a user cannot inject code.
   */
  function h(tag, props, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(props || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
      else if (key === 'class') el.className = value;
      else el.setAttribute(key, value === true ? '' : String(value));
    }
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) continue;
      el.append(child instanceof Node ? child : String(child));
    }
    return el;
  }

  // Line icons on a 24 x 24 grid: the same shapes the desktop app draws in its Ico class.
  const ICONS = {
    home: '<path d="M3 11 12 3l9 8M5 10v10h14V10M10 20v-6h4v6"/>',
    cal: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M3 10h18M8 3v4M16 3v4"/>',
    list: '<path d="M9 7h11M9 12h11M9 17h11"/><circle cx="4" cy="7" r="1.2" fill="currentColor" stroke="none"/>' +
      '<circle cx="4" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="4" cy="17" r="1.2" fill="currentColor" stroke="none"/>',
    users: '<circle cx="8" cy="8" r="4"/><path d="M14.5 20.5a6.5 6.5 0 0 0-13 0"/><circle cx="18" cy="9" r="3"/><path d="M23 19a4 4 0 0 0-8 0"/>',
    user: '<circle cx="12" cy="7" r="4"/><path d="M20 22a8 8 0 0 0-16 0"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/>',
    chart: '<path d="M6 20v-7M12 20V5M18 20V10"/>',
    out: '<path d="M10 4H5v16h5M10 12h11M17 8l4 4-4 4"/>',
    bell: '<path d="M18 11a6 7 0 0 0-12 0M6 11l-1 6M18 11l1 6M4 17h16M10 20h4"/>',
    card: '<rect x="2" y="5" width="20" height="14" rx="1.5"/><path d="M2 10h20"/>',
    next: '<path d="m9 5 7 7-7 7"/>',
    prev: '<path d="m15 5-7 7 7 7"/>',
    hash: '<path d="M9 4 7 20M17 4l-2 16M4 9h16M4 15h16"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    search: '<circle cx="11" cy="11" r="6"/><path d="m20 20-4.5-4.5"/>',
  };

  function icon(name, size) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    const px = String(size || 18);
    const attrs = {
      viewBox: '0 0 24 24', width: px, height: px, fill: 'none', stroke: 'currentColor', 'stroke-width': '2',
      'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true', focusable: 'false', class: 'ico',
    };
    for (const [k, v] of Object.entries(attrs)) svg.setAttribute(k, v);
    svg.innerHTML = ICONS[name] || ICONS.plus;   // fixed shapes from the list above, never user data
    return svg;
  }

  // Status colours, as in the desktop Badge class.
  const BADGE_COLORS = {
    'In Consultation': 'green', Confirmed: 'green', Available: 'green',
    Waiting: 'amber', Next: 'blue', Upcoming: 'blue', Scheduled: 'indigo',
    Cancelled: 'red', 'Not Available': 'red',
  };
  const badge = (text) => h('span', { class: 'badge badge-' + (BADGE_COLORS[text] || 'gray') }, text);

  /** Round picture with the first letters of the first two names, like the desktop Avatar. */
  function avatar(name, size) {
    const letters = name.split(' ').filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('');
    return h('span', { class: 'avatar avatar-' + size, 'aria-hidden': 'true' }, letters);
  }

  /** A button. kind is one of: primary, soft, danger, success, neutral, light. */
  const btn = (text, kind, onclick, mini) =>
    h('button', { type: 'button', class: 'btn btn-' + kind + (mini ? ' btn-mini' : ''), onclick }, text);
  const linkBtn = (text, kind, page) => h('a', { class: 'btn btn-' + kind, href: '#' + page }, text);

  /** A white card with a title and, on the right, a "View All" link or a small note. */
  function card(title, extra, ...body) {
    const head = h('div', { class: 'card-head' }, h('h2', null, title));
    if (extra && extra.go) head.append(h('a', { class: 'card-link', href: '#' + extra.go }, 'View All'));
    else if (extra && extra.note) head.append(h('span', { class: 'card-note' }, extra.note));
    return h('section', { class: 'card' }, head, body);
  }

  /** A table. Cells can be text or elements (badges, buttons). */
  function table(heads, rows, highlight) {
    const body = rows.length
      ? rows.map((cells, i) => h('tr', { class: i === highlight ? 'hl' : null }, cells.map((c) => h('td', null, c))))
      : h('tr', null, h('td', { class: 'empty', colspan: heads.length }, 'Nothing here yet.'));
    return h('div', { class: 'table-wrap' },
      h('table', null, h('thead', null, h('tr', null, heads.map((t) => h('th', { scope: 'col' }, t)))), h('tbody', null, body)));
  }

  /** A number card with a coloured icon, like stat() in the desktop app. With a page name it becomes a link. */
  function stat(color, ico, label, value, sub, page) {
    const parts = [
      h('span', { class: 'stat-ico bg-' + color }, icon(ico, 24)),
      h('span', { class: 'stat-text' },
        h('span', { class: 'stat-label' }, label), h('span', { class: 'stat-value' }, value), h('span', { class: 'stat-sub' }, sub)),
    ];
    if (page) parts.push(h('span', { class: 'stat-chev' }, icon('next', 18)));
    return page ? h('a', { class: 'stat', href: '#' + page }, parts) : h('div', { class: 'stat' }, parts);
  }

  /** Label/value rows, used for the profile and the appointment details. */
  const keyValues = (rows) => h('dl', null, rows.map(([k, v]) => h('div', { class: 'kv-row' }, h('dt', null, k), h('dd', null, v))));

  const labeled = (caption, control) => h('label', { class: 'field' }, h('span', { class: 'field-label' }, caption), control);
  const input = (name, attrs) => h('input', Object.assign({ name, class: 'input' }, attrs));
  const select = (name, options, selected) =>
    h('select', { name, class: 'input' }, options.map((o) => h('option', { value: o.value, selected: o.value === selected }, o.label)));
  const errLabel = () => h('p', { class: 'form-err', role: 'alert' });
  const valuesOf = (fields) => Object.fromEntries(Object.entries(fields).map(([k, el]) => [k, el.value]));

  // Keeps "09:00 AM", "Oct 22, 2026" and phone numbers on one line in narrow table columns.
  const nw = (text) => h('span', { class: 'nw' }, text);
  const range = (text) => text.split(' - ').flatMap((part, i) => (i ? [' - ', nw(part)] : [nw(part)]));
  const doc = (name) => name.replace(/^Dr\. /, 'Dr.\u00a0');   // never leave "Dr." alone at the end of a line

  const stack = (...items) => h('div', { class: 'stack' }, items);
  const grid = (...items) => h('div', { class: 'grid' }, items);
  const narrow = (el, width) => h('div', { class: 'narrow w' + width }, el);
  // =====================================================================
  //  POP-UP MESSAGE (the desktop ToastPane)
  // =====================================================================
  let toastTimer = 0;
  function toast(message, tone) {
    const red = tone === 'red' ? ' toast-red' : '';
    toastBox.querySelector('.toast-text').textContent = message;
    toastBox.className = 'toast show' + red;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastBox.className = 'toast' + red; }, 3000);
  }

  // =====================================================================
  //  TALKING TO THE JAVA SERVER
  // =====================================================================

  /** Without data: GET /api/<path>. With data: POST it as a form. Returns the server's JSON answer. */
  async function api(path, data) {
    const options = { credentials: 'same-origin', headers: { 'X-MediQueue': '1' } };
    if (data !== undefined) {
      options.method = 'POST';
      options.body = new URLSearchParams(data);
    }
    let response;
    try {
      response = await fetch('/api/' + path, options);
    } catch (e) {
      throw Object.assign(new Error('Cannot reach the MediQueue server. Is it still running?'), { status: 0 });
    }
    let reply = {};
    try { reply = await response.json(); } catch (e) { /* the answer was not JSON */ }
    if (!response.ok) {
      throw Object.assign(new Error(reply.error || 'Something went wrong (error ' + response.status + ').'), { status: response.status });
    }
    return reply;
  }

  /**
   * Sends a change to the server, then shows the reply message and redraws the page with the new data.
   * options.go: page to open afterwards; options.done: run after success; options.error: show errors yourself.
   */
  async function send(path, data, button, options) {
    const opts = options || {};
    if (button) button.disabled = true;
    try {
      const reply = await api(path, data);
      if (reply.state) state = reply.state;
      if (opts.done) opts.done(reply);
      if (opts.go && currentPage() !== opts.go) location.hash = opts.go;   // the hashchange event redraws
      else render();
      if (reply.message) toast(reply.message, reply.tone);
      return reply;
    } catch (error) {
      if (error.status === 401) signedOut('Please sign in again to continue.');
      else if (opts.error) opts.error(error.message);
      else toast(error.message, 'red');
      return null;
    } finally {
      if (button) button.disabled = false;
    }
  }
// =====================================================================
  //  WHAT THE PAGE REMEMBERS
  // =====================================================================
  let state = null;          // everything the server sent for the signed-in user (null = nobody signed in)
  let authView = 'Welcome';    // which sign-in card is showing: login, register or forgot
  let loginRole = 'patient'; // the Patient / Admin choice on the sign-in card
  let prefillUser = '';      // username to show again after a password reset
  let calMonth = null;       // month shown in "My Schedule": { year, month } with month 0-11
  let scheduleDoctor = '';   // doctor chosen on the Schedules page
  let patientSearch = '';    // text typed in the Patients search box
  let navOpen = false;       // side menu open (small screens only)
  const modals = [];         // open dialogs, newest last

  const NAV = {
    patient: [['home', 'Home', 'home'], ['book', 'Book Appointment', 'cal'], ['appointments', 'My Appointments', 'list'],
      ['queue', 'Queue Status', 'users'], ['profile', 'Profile', 'user']],
    admin: [['home', 'Dashboard', 'home'], ['appointments', 'Appointments', 'cal'], ['queue', 'Queue', 'list'],
      ['patients', 'Patients', 'users'], ['doctors', 'Doctors', 'user'], ['schedules', 'Schedules', 'clock'], ['reports', 'Reports', 'chart']],
  };
  const isAdmin = () => state !== null && state.user.role === 'admin';

  /** The page named after # in the address, if this user has such a page; otherwise home. */
  function currentPage() {
    const wanted = location.hash.slice(1);
    return state && NAV[state.user.role].some((n) => n[0] === wanted) ? wanted : 'home';
  }

  function render() {
    if (state) renderApp();
    else renderAuth();
  }

// =====================================================================
  //  SIGN IN / REGISTER / FORGOT PASSWORD
  // =====================================================================
  function renderAuth() {
    document.title = 'MediQueue';

    let view;

    switch (authView) {
        case 'welcome':
            view = welcomeView();
            break;
        case 'register':
            view = registerView();
            break;
        case 'forgot':
            view = forgotView();
            break;
        default:
            view = loginView();
            break;
    }

    app.replaceChildren(
        h('main', { class: 'auth' },
            h('div', { class: 'auth-card' },
                h('div', { class: 'auth-brand' },
                    h('span', { class: 'mark mark-lg' }, icon('plus', 30)),
                    h('h1', null, 'MediQueue'),
                    h('p', null, 'Clinic Appointment & Queue System')
                ),
                view
            )
        )
    );

    const empty = Array.from(app.querySelectorAll('input'))
        .find((i) => !i.value);

    if (empty && window.matchMedia('(min-width: 861px)').matches) {
        empty.focus();
    }
    function welcomeView() {
    return h('div', { class: 'auth-welcome' },
        h('h2', { class: 'auth-title' }, 'Welcome to MediQueue'),

        h('p', { class: 'auth-text' },
            'Manage your clinic appointments and queue conveniently.'),

        h('div', { class: 'btn-stack auth-welcome-actions' },

            h('button', {
                type: 'button',
                class: 'btn btn-primary btn-block',
                onclick: () => showAuth('login')
            }, 'Login'),

            h('button', {
                type: 'button',
                class: 'btn btn-soft btn-block',
                onclick: () => {
                    loginRole = 'patient';
                    showAuth('register');
                }
            }, 'Register')
        ),

        h('p', { class: 'auth-hint' },
            'New accounts are available for patients only.')
    );
}
}

  function showAuth(view) {
    authView = view;
    renderAuth();
  }

  const backLink = () =>
    h('div', null, h('button', { type: 'button', class: 'link-btn', onclick: () => showAuth('login') }, '← Back to sign in'));

  function loginView() {
    const user = input('username', { autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', value: prefillUser });
    const pass = input('password', { type: 'password', autocomplete: 'current-password' });
    const err = errLabel();
    const hint = h('p', { class: 'auth-hint' });
const regLink = h('button', {
    type: 'button',
    class: 'link-btn',
    onclick: () => {
        loginRole = 'patient';
        showAuth('register');
    }
}, 'New patient? Register');    const roles = ['patient', 'admin'].map((r) =>
      h('button', { type: 'button', class: 'role-btn', 'data-role': r, onclick: () => setRole(r) }, r === 'patient' ? 'Patient' : 'Admin'));

    function setRole(r) {   // the desktop setLoginRole()
      loginRole = r;
      err.textContent = '';
      for (const b of roles) {
        const on = b.dataset.role === r;
        b.classList.toggle('on', on);
        b.setAttribute('aria-pressed', String(on));
      }
      regLink.hidden = r !== 'patient';
      hint.textContent = r === 'admin' ? 'Authorized clinic staff only.' : 'Sign in with your registered patient account.';
    }
    setRole(loginRole);

    const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block' }, 'Sign In');
    const form = h('form', { class: 'form', novalidate: true },
      h('div', { class: 'roles', role: 'group', 'aria-label': 'Sign in as' }, roles),
      labeled('Username', user), labeled('Password', pass), err, submit,
      h('div', { class: 'auth-links' },
        h('button', { type: 'button', class: 'link-btn', onclick: () => showAuth('forgot') }, 'Forgot password?'), regLink),
      hint);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      submit.disabled = true;
      try {
        signedIn(await api('login', { username: user.value, password: pass.value, role: loginRole }));
      } catch (error) {
        err.textContent = error.message;
        pass.value = '';
        pass.focus();
      } finally {
        submit.disabled = false;
      }
    });
    return form;
  }

  function registerView() {
    const f = {
      name: input('name', { autocomplete: 'name', maxlength: 60 }),
      contact: input('contact', { autocomplete: 'tel', inputmode: 'tel', maxlength: 20 }),
      username: input('username', { autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', maxlength: 20 }),
      password: input('password', { type: 'password', autocomplete: 'new-password' }),
      password2: input('password2', { type: 'password', autocomplete: 'new-password' }),
    };
    const err = errLabel();
    const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block' }, 'Create Account');
    const form = h('form', { class: 'form form-tight', novalidate: true },
      h('h2', { class: 'auth-title' }, 'Create a patient account'),
      labeled('Full name', f.name), labeled('Contact number', f.contact), labeled('Username', f.username),
      labeled('Password (6+ characters)', f.password), labeled('Confirm password', f.password2),
      err, submit, backLink());
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      submit.disabled = true;
      try {
        signedIn(await api('register', valuesOf(f)));
      } catch (error) {
        err.textContent = error.message;
      } finally {
        submit.disabled = false;
      }
    });
    return form;
  }

  function forgotView() {
    const f = {
      username: input('username', { autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false' }),
      contact: input('contact', { autocomplete: 'tel', inputmode: 'tel' }),
      password: input('password', { type: 'password', autocomplete: 'new-password' }),
      password2: input('password2', { type: 'password', autocomplete: 'new-password' }),
    };
    const err = errLabel();
    const submit = h('button', { type: 'submit', class: 'btn btn-primary btn-block' }, 'Reset Password');
    const form = h('form', { class: 'form form-tight', novalidate: true },
      h('h2', { class: 'auth-title' }, 'Reset your password'),
      h('p', { class: 'auth-text' }, 'Enter your username and the contact number you registered with.'),
      labeled('Username', f.username), labeled('Registered contact number', f.contact),
      labeled('New password (6+ characters)', f.password), labeled('Confirm new password', f.password2),
      err, submit, backLink());
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      submit.disabled = true;
      try {
        const reply = await api('reset', valuesOf(f));
        prefillUser = reply.username;
        loginRole = 'patient';
        showAuth('login');
        toast(reply.message, reply.tone);
      } catch (error) {
        err.textContent = error.message;
      } finally {
        submit.disabled = false;
      }
    });
    return form;
  }

  /** After a successful sign-in or registration (the desktop startSession). */
  function signedIn(reply) {
    state = reply.state;
    authView = 'login';
    prefillUser = '';
    patientSearch = '';
    scheduleDoctor = '';
    calMonth = null;
    navOpen = false;
    history.replaceState(null, '', '#home');
    render();
    toast(reply.message, reply.tone);
  }

  /** Back to the sign-in card, for example when the server was restarted and forgot the sign-in. */
  function signedOut(message, tone) {
    state = null;
    closeAllModals();

    authView = 'welcome';
    loginRole = 'patient';

    navOpen = false;
    prefillUser = '';
    patientSearch = '';
    scheduleDoctor = '';
    calMonth = null;

    history.replaceState(null, '', location.pathname);

    render();

    if (message) {
        toast(message, tone || 'red');
    }
}

  async function logout() {
    try {
      const reply = await api('logout', {});
      signedOut(reply.message, reply.tone);
    } catch (error) {
      signedOut('You have been logged out.', 'green');
    }
  }

 // =====================================================================
  //  APP SHELL: sidebar + header + page
  // =====================================================================
  function renderApp() {
    const page = currentPage();
    const items = NAV[state.user.role];
    const label = items.find((n) => n[0] === page)[1];
    document.title = label + ' - MediQueue';

    const sidebar = h('aside', { class: 'sidebar', id: 'sidebar' },
      h('div', { class: 'brand' },
        h('span', { class: 'mark' }, icon('plus', 22)),
        h('span', { class: 'brand-text' }, h('strong', null, 'MediQueue'), h('small', null, 'Clinic Appointment &', h('br'), 'Queue System'))),
      h('nav', { class: 'nav', 'aria-label': 'Main menu' }, items.map(([key, text, ico]) =>
        h('a', {
          class: 'nav-item' + (key === page ? ' on' : ''), href: '#' + key, 'aria-current': key === page ? 'page' : null,
          onclick: () => { if (key === currentPage()) setNav(false); },
        }, icon(ico, 18), h('span', null, text)))),
      h('div', { class: 'side-foot' },
        avatar(state.user.name, 34),
        h('span', { class: 'side-user' }, h('strong', null, state.user.firstName), h('small', null, state.user.roleLabel)),
        h('button', { type: 'button', class: 'icon-btn', title: 'Logout', 'aria-label': 'Logout', onclick: logout }, icon('out', 20))));

    const topbar = h('div', { class: 'topbar' },
      h('button', {
        type: 'button', class: 'icon-btn menu-btn', 'aria-label': 'Open menu', 'aria-controls': 'sidebar',
        'aria-expanded': String(navOpen), onclick: () => setNav(!navOpen),
      }, icon('menu', 22)),
      h('span', { class: 'mark mark-sm' }, icon('plus', 16)),
      h('strong', null, 'MediQueue'));

    app.replaceChildren(h('div', { class: 'shell' + (navOpen ? ' nav-open' : '') },
      sidebar,
      h('div', { class: 'scrim', onclick: () => setNav(false) }),
      h('div', { class: 'main' }, topbar, pageHeader(page, label), h('main', { class: 'content' }, pageBody(page)))));
  }

  /** Opens or closes the slide-out menu on small screens. */
  function setNav(open) {
    navOpen = open;
    const shell = app.querySelector('.shell');
    if (shell) shell.classList.toggle('nav-open', open);
    const button = app.querySelector('.menu-btn');
    if (button) button.setAttribute('aria-expanded', String(open));
  }

  function pageHeader(page, label) {
    let title;
    let sub;
    if (isAdmin()) {
      title = page === 'home' ? 'Admin Dashboard' : label;
      sub = 'Manage appointments, queues, patients, and doctor schedules.';
    } else if (page === 'home') {
      const hr = state.now.hour;
      title = (hr < 12 ? 'Good morning' : hr < 18 ? 'Good afternoon' : 'Good evening') + ', ' + state.user.firstName + '!';
      sub = "Take care of your health. We're here for you.";
    } else {
      title = label;
      sub = 'MediQueue patient portal';
    }
    return h('header', { class: 'page-head' },
      h('div', null, h('h1', null, title), h('p', null, sub)),
      h('div', { class: 'head-right' },
        isAdmin() ? h('span', { class: 'chip' }, icon('cal', 16), state.now.dateLabel) : h('span', { class: 'bell' }, icon('bell', 22)),
        avatar(state.user.name, 38),
        h('span', { class: 'head-user' }, h('strong', null, state.user.name), h('small', null, state.user.roleLabel))));
  }

   function pageBody(page) {
    if (!isAdmin()) {
      switch (page) {
        case 'home': return patientHome();
        case 'book': return narrow(bookCard(), 560);
        case 'appointments': return narrow(myAppointmentsCard(), 820);
        case 'queue': return narrow(queueCard(), 700);
        default: return narrow(profileCard(), 520);
      }
    }
    switch (page) {
      case 'home': return stack(
  adminStats(),
  grid(todayCard(), queueOverviewCard(), quickActionsCard()),
  grid(doctorsCard(), recentPatientsCard(), summaryCard()),
  narrow(adminAccountsCard(), 560)
);
      case 'appointments': return appointmentsCard();
      case 'queue': return stack(counters(), queueManageCard());
      case 'patients': return patientsCard();
      case 'doctors': return doctorsCard();
      case 'schedules': return schedulesCard();
      default: return stack(adminStats(), narrow(summaryCard(), 520));
    }
  }
// =====================================================================
  //  DIALOGS
  // =====================================================================

  /** Shows content in a dialog over the page. Returns an object with close(). */
  function openModal(content, width, label) {
    const box = h('div', { class: 'dialog w' + width, role: 'dialog', 'aria-modal': 'true', 'aria-label': label }, content);
    const overlay = h('div', { class: 'overlay' }, box);
    const previous = document.activeElement;
    const m = {
      close() {
        const i = modals.indexOf(m);
        if (i === -1) return;
        modals.splice(i, 1);
        overlay.remove();
        app.inert = modals.length > 0;
        if (previous && document.contains(previous)) previous.focus();
      },
    };
    overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) m.close(); });
    document.body.append(overlay);
    modals.push(m);
    app.inert = true;   // the page behind the dialog cannot be clicked or tabbed into
    const first = box.querySelector('input:not([readonly]), select, button');
    if (first) first.focus();
    return m;
  }

  function closeAllModals() {
    while (modals.length) modals[modals.length - 1].close();
  }

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && modals.length) modals[modals.length - 1].close();
  });

  /** A Yes / No question (the desktop ask()). */
  function ask(title, message, onYes) {
    const m = openModal(h('div', { class: 'dialog-body' },
      h('h2', { class: 'ask-title' }, title),
      h('p', { class: 'muted' }, message),
      h('div', { class: 'btn-row' },
        btn('No', 'neutral', () => m.close()),
        btn('Yes', 'danger', () => { m.close(); onYes(); }))), 380, title);
  }

// =====================================================================
  //  CLINIC STAFF PAGES
  // =====================================================================
  function adminStats() {
    const s = state.stats;
    return h('div', { class: 'stats stats-4' },
      stat('blue', 'cal', 'Total Appointments', String(s.appointments), 'All bookings'),
      stat('green', 'users', 'Total Patients', String(s.patients), 'Registered'),
      stat('purple', 'hash', 'Current Queue', s.currentQueue ? '# ' + s.currentQueue : '-', 'OPD - General Medicine'),
      stat('rose', 'user', 'Doctors On Duty', s.onDuty + ' / ' + s.doctorCount, 'Today'));
  }

  function todayCard() {
    const rows = state.appointments.filter((a) => a.date === state.now.date).map((a) => [nw(a.timeLabel), a.patient, doc(a.doctor), badge(a.status)]);
    return card("Today's Appointments", { go: 'appointments' }, table(['Time', 'Patient', 'Doctor', 'Status'], rows));
  }

  function queueOverviewCard() {
    const rows = state.queue.rows.map((q) => [q.no, q.name, badge(q.status)]);
    return card('Queue Overview', { go: 'queue' }, table(['Queue No.', 'Patient', 'Status'], rows));
  }

  function quickActionsCard() {
    return card('Quick Actions', null, h('div', { class: 'btn-stack' },
      linkBtn('Manage Appointments', 'primary', 'appointments'), linkBtn('Manage Schedule', 'soft', 'schedules'),
      linkBtn('Manage Patients', 'soft', 'patients'), linkBtn('Manage Queue', 'soft', 'queue')));
  }

  function adminAccountsCard() {
  const f = {
    name: input('name', { maxlength: 60, autocomplete: 'off' }),
    contact: input('contact', { maxlength: 20, inputmode: 'tel', autocomplete: 'off' }),
    username: input('username', { maxlength: 20, autocomplete: 'off', autocapitalize: 'none' }),
    password: input('password', { type: 'password', autocomplete: 'new-password' }),
    password2: input('password2', { type: 'password', autocomplete: 'new-password' }),
  };

  const err = errLabel();
  const submit = h('button', {
    type: 'submit',
    class: 'btn btn-primary'
  }, 'Create Admin Account');

  const form = h('form', { class: 'form', novalidate: true },
    labeled('Full name', f.name),
    labeled('Contact number', f.contact),
    labeled('Username', f.username),
    labeled('Password (6+ characters)', f.password),
    labeled('Confirm password', f.password2),
    err,
    submit
  );

  form.addEventListener('submit', (e) => {
    e.preventDefault();

    send('admin/create', valuesOf(f), submit, {
      done: () => form.reset(),
      error: (message) => {
        err.textContent = message;
      }
    });
  });

  return card('Admin Account Management', null,
    h('p', { class: 'muted' },
      'Create additional accounts for authorized clinic staff.'),
    form
  );
}

  function doctorsCard() {
    const rows = state.doctors.map((d) => [doc(d.name), d.spec, range(d.hours)]);
    return card('Doctor Schedule', currentPage() === 'home' ? { go: 'doctors' } : null, table(['Doctor', 'Specialization', 'Available Today'], rows));
  }

  function recentPatientsCard() {
    const rows = state.patients.slice(0, 5).map((p) => [p.name, nw(p.contact), nw(p.visit)]);
    return card('Recent Patients', { go: 'patients' }, table(['Patient', 'Contact No.', 'Last Visit'], rows));
  }

  function summaryCard() {
    const s = state.summary;
    const rows = [['Active Appointments', s.active, 'blue'], ['In Queue', s.inQueue, 'green'],
      ['Completed Today', s.completed, 'purple'], ['Cancelled', s.cancelled, 'rose']];
    return card('System Summary', null, h('ul', { class: 'summary' },
      rows.map(([text, n, color]) => h('li', null, h('span', { class: 'dot bg-' + color }), h('span', null, text), h('strong', null, String(n))))));
  }

  function appointmentsCard() {
    const rows = state.appointments.map((a) => [a.id, a.patient, doc(a.doctor), nw(a.dateLabel), nw(a.timeLabel), badge(a.status),
      h('div', { class: 'actions' },
        a.canConfirm ? btn('Confirm', 'success', (e) => send('confirm', { id: a.id }, e.currentTarget), true) : null,
        a.live ? btn('Cancel', 'danger', () => cancelDialog(a), true) : null)]);
    return card('Upcoming Appointments', null, table(['ID', 'Patient Name', 'Doctor', 'Date', 'Time', 'Status', 'Actions'], rows));
  }

  function counters() {
    const c = state.counters;
    return h('div', { class: 'counters' },
      [['Waiting', c.waiting, 'green'], ['In Consultation', c.inConsultation, 'blue'], ['Completed', c.completed, 'slate']].map(([text, n, color]) =>
        h('div', { class: 'counter bg-' + color }, h('strong', null, String(n)), h('span', null, text))));
  }

  function queueManageCard() {
    const rows = state.queue.rows.map((q) => {
      let action = null;
      const who = { index: q.index, name: q.name };
      if (q.status === 'In Consultation') action = btn('Complete', 'success', (e) => send('queue/complete', who, e.currentTarget), true);
      else if (q.status !== 'Completed') action = btn('Call', 'primary', (e) => send('queue/call', who, e.currentTarget), true);
      return [q.no, q.name, doc(q.doctor), badge(q.status), h('div', { class: 'actions' }, action)];
    });
    return card('Patient Queue', null,
      h('div', { class: 'toolbar' }, btn('Call Next Patient', 'primary', (e) => send('queue/next', {}, e.currentTarget))),
      table(['Queue No.', 'Patient Name', 'Doctor', 'Status', 'Action'], rows));
  }

  function patientsCard() {
    const search = input('search', {
      type: 'search', value: patientSearch, placeholder: 'Search by name or patient ID', 'aria-label': 'Search patients', autocomplete: 'off',
    });
    const tableBox = h('div');
    const fill = () => {   // like fillPatientTable(): only the table is redrawn while typing
      const q = patientSearch.trim().toLowerCase();
      const rows = state.patients
        .filter((p) => !q || (p.name + ' ' + p.id).toLowerCase().includes(q))
        .map((p) => [p.id, p.name, p.age || '-', p.gender || '-', nw(p.contact),
          h('div', { class: 'actions' },
            btn('Edit', 'primary', () => patientDialog(p), true),
            btn('Delete', 'danger', () => ask('Delete Patient', 'Delete the record of ' + p.name + '?', () => send('patients/delete', { id: p.id })), true))]);
      tableBox.replaceChildren(table(['Patient ID', 'Name', 'Age', 'Gender', 'Contact', 'Actions'], rows));
    };
    search.addEventListener('input', () => {
      patientSearch = search.value;
      fill();
    });
    fill();
    return card('Patient Information & Database', null,
      h('div', { class: 'toolbar toolbar-split' },
        h('div', { class: 'search' }, icon('search', 16), search),
        btn('Add Patient', 'primary', () => patientDialog(null))),
      tableBox);
  }

  function schedulesCard() {
    if (!scheduleDoctor) scheduleDoctor = state.doctors[0].name;
    const pick = select('doctor', state.doctors.map((d) => ({ value: d.name, label: d.name })), scheduleDoctor);
    pick.setAttribute('aria-label', 'Doctor');
    const rows = state.schedules.filter((s) => s.doctor === scheduleDoctor).map((s) => [nw(s.dateLabel), s.day, range(s.slots),
      badge(s.ok ? 'Available' : 'Not Available'),
      h('div', { class: 'actions' },
        btn(s.ok ? 'Mark Off' : 'Mark On', 'primary', (e) => send('schedules/toggle', { id: s.id }, e.currentTarget), true),
        btn('Remove', 'danger', (e) => send('schedules/remove', { id: s.id }, e.currentTarget), true))]);
    return card('Doctor Schedule Management', null,
      h('div', { class: 'toolbar' }, pick,
        btn('Load Schedule', 'primary', () => { scheduleDoctor = pick.value; render(); }),
        btn('Add Schedule', 'soft', () => { scheduleDoctor = pick.value; scheduleDialog(); })),
      table(['Date', 'Day', 'Available Time Slots', 'Status', 'Actions'], rows));
  }

  /** Add Patient (p = null) or Edit Patient. */
  function patientDialog(p) {
    const f = {
      name: input('name', { value: p ? p.name : '', maxlength: 60, autocomplete: 'off' }),
      age: input('age', { value: p ? p.age : '', inputmode: 'numeric', maxlength: 3, autocomplete: 'off' }),
      gender: select('gender', ['Female', 'Male', 'Other'].map((g) => ({ value: g, label: g })), p && p.gender ? p.gender : 'Female'),
      contact: input('contact', { value: p ? p.contact : '', inputmode: 'tel', maxlength: 20, autocomplete: 'off' }),
    };
    const err = errLabel();
    const save = h('button', { type: 'submit', class: 'btn btn-primary' }, 'Save');
    const title = p ? 'Edit Patient' : 'Add Patient';
    const form = h('form', { class: 'form', novalidate: true },
      h('h2', { class: 'dlg-title' }, title),
      labeled('Full name', f.name), labeled('Age', f.age), labeled('Gender', f.gender), labeled('Contact number', f.contact), err,
      h('div', { class: 'btn-row' }, save, btn('Cancel', 'neutral', () => m.close())));
    const m = openModal(form, 400, title);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      send('patients/save', Object.assign({ id: p ? p.id : '' }, valuesOf(f)), save, {
        done: () => m.close(),
        error: (message) => { err.textContent = message; },
      });
    });
  }

  function scheduleDialog() {
    const o = state.scheduleOptions;
    const doctor = input('doctor', { value: scheduleDoctor, readonly: true });
    const date = select('date', o.dates);
    const slots = select('slots', o.slots.map((s) => ({ value: s, label: s })));
    const add = h('button', { type: 'submit', class: 'btn btn-primary' }, 'Add Schedule');
    const form = h('form', { class: 'form', novalidate: true },
      h('h2', { class: 'dlg-title' }, 'Add Schedule'),
      labeled('Doctor', doctor), labeled('Date', date), labeled('Available time', slots),
      h('div', { class: 'btn-row' }, add, btn('Cancel', 'neutral', () => m.close())));
    const m = openModal(form, 400, 'Add Schedule');
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      send('schedules/add', { doctor: scheduleDoctor, date: date.value, slots: slots.value }, add, { done: () => m.close() });
    });
  }
// =====================================================================
  //  PATIENT PAGES
  // =====================================================================
  function patientHome() {
    const up = state.upcoming;
    const myNumber = state.queue.myNumber;
    return stack(
      h('div', { class: 'stats stats-3' },
        stat('blue', 'cal', 'Upcoming Appointment', up ? up.dateLabel : 'None', up ? up.doctor : 'Book one today', up ? 'appointments' : 'book'),
        stat('green', 'users', 'Current Queue', '# ' + (myNumber || '-'), 'OPD - General Medicine', 'queue'),
        stat('purple', 'card', 'Reservation Fee', state.fee, '(per appointment)', 'profile')),
      grid(myAppointmentsCard(), queueCard(), scheduleCard()));
  }

  function apptItem(a) {
    return h('div', { class: 'appt' },
      h('div', { class: 'appt-date' }, h('span', { class: 'appt-mo' }, a.month), h('span', { class: 'appt-dy' }, a.day)),
      h('div', { class: 'appt-info' }, h('strong', null, a.doctor), h('span', null, a.spec), h('span', null, a.timeLabel)),
      h('div', { class: 'appt-side' }, badge(a.status), a.live ? btn('Cancel', 'danger', () => cancelDialog(a), true) : null));
  }

  function myAppointmentsCard() {
    const items = state.appointments.length ? state.appointments.map(apptItem) : h('p', { class: 'muted' }, 'No appointments yet.');
    return card('My Appointments', currentPage() === 'appointments' ? null : { go: 'appointments' }, h('div', { class: 'list' }, items));
  }

  function queueCard() {
    const q = state.queue;
    const box = h('div', { class: 'qbox' },
      h('span', { class: 'qbox-ico' }, icon('users', 22)),
      h('div', null,
        h('div', { class: 'qbox-label' }, 'Your Queue Number'),
        h('div', { class: 'qbox-num' }, '# ' + (q.myNumber || '-')),
        h('div', { class: 'qbox-sub' }, 'OPD - General Medicine')));
    const rows = q.rows.map((r) => [String(r.no), r.name, badge(r.status)]);
    return card('Queue Status', { note: 'Last updated: ' + state.now.time }, box, table(['No.', 'Patient Name', 'Status'], rows, q.myNumber - 1));
  }

  /** The month calendar with your appointment days marked (the desktop wSchedule). */
  function scheduleCard() {
    const [ty, tm] = state.now.date.split('-').map(Number);
    if (!calMonth) calMonth = { year: ty, month: tm - 1 };
    const { year, month } = calMonth;
    const marks = new Set(state.appointments.filter((a) => a.live).map((a) => a.date));
    const next = state.upcoming ? state.upcoming.date : null;
    const pad = (n) => String(n).padStart(2, '0');

    const cells = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'].map((d) => h('span', { class: 'cal-head' }, d));
    const firstDay = new Date(year, month, 1).getDay();
    for (let i = 0; i < firstDay; i++) cells.push(h('span'));
    const days = new Date(year, month + 1, 0).getDate();
    for (let d = 1; d <= days; d++) {
      const iso = year + '-' + pad(month + 1) + '-' + pad(d);
      cells.push(h('span', { class: 'cal-day' + (iso === next ? ' sel' : marks.has(iso) ? ' mark' : '') }, String(d)));
    }
    const move = (delta) => {
      const m = new Date(year, month + delta, 1);
      calMonth = { year: m.getFullYear(), month: m.getMonth() };
      render();
    };
    const monthName = new Date(year, month, 1).toLocaleString('en-US', { month: 'long' });
    return card('My Schedule', null,
      h('div', { class: 'cal-nav' },
        h('button', { type: 'button', class: 'btn btn-light btn-mini', 'aria-label': 'Previous month', onclick: () => move(-1) }, icon('prev', 14)),
        h('strong', { 'aria-live': 'polite' }, monthName + ' ' + year),
        h('button', { type: 'button', class: 'btn btn-light btn-mini', 'aria-label': 'Next month', onclick: () => move(1) }, icon('next', 14))),
      h('div', { class: 'cal-grid' }, cells),
      h('h3', { class: 'sub-title' }, 'Quick Actions'),
      h('div', { class: 'btn-stack' }, linkBtn('Book Appointment', 'primary', 'book'), linkBtn('View Queue', 'soft', 'queue')));
  }

  function bookCard() {
    const o = state.booking;
    const doctor = select('doctor', o.doctors);
    const date = select('date', o.dates);
    const time = select('time', o.times);
    const submit = h('button', { type: 'submit', class: 'btn btn-primary' }, 'Book Appointment');
    const form = h('form', { class: 'form', novalidate: true },
      labeled('Doctor', doctor), labeled('Date (clinic is closed on Sundays)', date), labeled('Time slot', time), submit);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      send('book', { doctor: doctor.value, date: date.value, time: time.value }, submit, { go: 'appointments' });
    });
    return card('Book Appointment', null, form);
  }

  function profileCard() {
    const p = state.profile;
    return card('Profile', null, keyValues([
      ['Name', p.name], ['Patient ID', p.patientId], ['Contact', p.contact], ['Reservation fee', state.fee + ' per appointment']]));
  }

  /** Cancel Appointment: details and a warning, then a Confirm Cancellation question. */
  function cancelDialog(a) {
    const m = openModal(h('div', { class: 'dialog-body' },
      h('h2', { class: 'dlg-title' }, 'Cancel Appointment'),
      h('h3', { class: 'dlg-sub' }, 'Appointment Details'),
      keyValues([['Appointment ID', a.id], ['Patient Name', a.patient], ['Doctor', a.doctor], ['Date', a.dateLabel],
        ['Time', a.timeLabel], ['Status', badge(a.status)]]),
      h('p', { class: 'warn' }, 'Are you sure you want to cancel this appointment?', h('br'), 'This action cannot be undone.'),
      h('div', { class: 'btn-row' },
        btn('Cancel Appointment', 'danger', () =>
          ask('Confirm Cancellation', 'Do you want to cancel this appointment?', () => send('cancel', { id: a.id }, null, { done: closeAllModals }))),
        btn('Back', 'neutral', () => m.close()))), 440, 'Cancel Appointment');
  }
 // =====================================================================
  //  KEEPING THE PAGE UP TO DATE
  // =====================================================================

  // Pages with forms are not redrawn on their own, so nothing you are typing gets wiped.
  const PAGES_WITH_FORMS = ['book', 'patients', 'schedules'];

  // Every 15 seconds, fetch the latest data so the queue moves on screen when staff call the next patient.
  setInterval(async () => {
    if (!state || document.hidden || modals.length || PAGES_WITH_FORMS.includes(currentPage())) return;
    const focused = document.activeElement;
    if (focused && /^(INPUT|SELECT|TEXTAREA)$/.test(focused.tagName)) return;
    try {
      const reply = await api('state');
      if (!state || modals.length) return;   // signed out or a dialog opened in the meantime
      if (!reply.state) {                     // the server no longer knows this sign-in (restarted, or 12 hours passed)
        signedOut('Your session has ended. Please sign in again.');
        return;
      }
      const changed = JSON.stringify(reply.state) !== JSON.stringify(state);
      state = reply.state;
      if (changed) render();
    } catch (error) {
      if (error.status === 401) signedOut('Your session has ended. Please sign in again.');
    }
  }, 15000);

  window.addEventListener('hashchange', () => {
    if (!state) return;
    closeAllModals();
    navOpen = false;
    render();
    window.scrollTo(0, 0);
  });

  // Start: if this browser is still signed in, the server sends the state; otherwise show the sign-in card.
  (async function start() {
    try {
      state = (await api('state')).state;
    } catch (error) {
      state = null;
      if (error.status !== 401) toast(error.message, 'red');
    }
    render();
  })();
})();
