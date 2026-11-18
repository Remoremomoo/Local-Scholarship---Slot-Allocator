/* ==========================================================================
   Local Scholarship - front end for the Java allocation engine

   Accounts: student register/login + admin login.
   Sessions are HttpOnly cookies set by the Java server, so the browser
   never sees or stores a token in JS.
   ========================================================================== */

const api = {
  config()        { return fetch('/api/config').then(check); },
  me()            { return fetch('/api/me').then(check); },
  login(fields)   { return post('/api/login', fields); },
  register(fields){ return post('/api/register', fields); },
  logout()        { return fetch('/api/logout', { method: 'POST' }).then(check); },
  forgotPassword(identifier) { return post('/api/forgot-password', { identifier }); },
  resetPassword(fields)      { return post('/api/reset-password', fields); },
  application()   { return fetch('/api/application').then(check); },
  results()       { return fetch('/api/results').then(check); },
  submit(fields)  { return post('/api/applicants', fields); },
  reset()         { return fetch('/api/reset', { method: 'POST' }).then(check); }
};

function post(url, fields) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(fields)
  }).then(check);
}

async function check(response) {
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(data.error || 'The server rejected the request.');
    error.status = response.status;
    throw error;
  }
  return data;
}

const money = (n) => '₱' + Number(n).toLocaleString('en-PH');
const score = (n) => Number(n).toFixed(2);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const homeFor = (role) => (role === 'admin' ? 'admin.html' : 'apply.html');
const roleLabel = (role) => (role === 'admin' ? 'Administrator' : 'Student');

function showError(el, message) {
  if (!el) return;
  el.textContent = message;
  el.classList.add('show');
}

/* --------------------------------------------------------------------------
   Session chip sa nav
   -------------------------------------------------------------------------- */

async function renderSession() {
  const slot = document.getElementById('nav-session');
  if (!slot) return null;

  let user = null;
  try {
    user = (await api.me()).user;
  } catch (err) {
    user = null;
  }

  if (!user) {
    slot.innerHTML = '<a class="btn btn-ghost" href="login.html">Sign in</a>';
    return null;
  }

  slot.innerHTML = `
    <a class="navlink" href="${homeFor(user.role)}">${user.role === 'admin' ? 'Admin console' : 'My application'}</a>
    <span class="nav-user">${esc(user.name)}<small>${roleLabel(user.role)}</small></span>
    <button class="btn-small" id="nav-logout" type="button">Sign out</button>
  `;

  document.getElementById('nav-logout').addEventListener('click', () => {
    api.logout().catch(() => {}).then(() => { location.href = 'index.html'; });
  });

  return user;
}

/** Page guard: redirect sa login kung hindi naka-sign in / maling role. */
async function requireRole(role) {
  let user = null;
  try {
    user = (await api.me()).user;
  } catch (err) {
    user = null;
  }

  if (!user) {
    location.replace('login.html');
    return null;
  }
  if (role && user.role !== role) {
    location.replace(homeFor(user.role));
    return null;
  }
  return user;
}

/* --------------------------------------------------------------------------
   Landing page
   -------------------------------------------------------------------------- */

function initLanding() {
  const slotsEl = document.getElementById('slot-count');
  const statsEl = document.getElementById('criteria-stats');
  if (!slotsEl && !statsEl) return;

  // Keep the hero copy readable even if the server is unreachable.
  if (slotsEl) slotsEl.textContent = '25 scholarships';

  api.config()
    .then((config) => {
      if (slotsEl) slotsEl.textContent = config.slots + ' scholarships';
      if (statsEl) {
        statsEl.innerHTML = config.criteria.map((c) => `
          <div class="stat">
            <b>${Math.round(c.percent)}%</b>
            <span>${esc(c.name)}</span>
          </div>
        `).join('');
      }
    })
    .catch(() => {
      if (statsEl) {
        statsEl.innerHTML = '<p class="empty">Could not reach the allocator server. Run the Java server, then reload.</p>';
      }
    });
}

/* --------------------------------------------------------------------------
   Sign in (student / admin tabs)
   -------------------------------------------------------------------------- */

function initLogin() {
  const form = document.getElementById('login-form');
  if (!form) return;

  const errorBox = document.getElementById('auth-error');
  const submitBtn = document.getElementById('login-btn');
  const tabs = [...document.querySelectorAll('.tab')];
  let role = 'student';

  // Kung naka-sign in na, deretso sa tamang page.
  api.me()
    .then(({ user }) => { if (user) location.replace(homeFor(user.role)); })
    .catch(() => {});

  tabs.forEach((tab) => tab.addEventListener('click', () => {
    role = tab.dataset.role;
    tabs.forEach((t) => {
      const active = t === tab;
      t.classList.toggle('active', active);
      t.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    submitBtn.textContent = role === 'admin' ? 'Sign in as admin' : 'Sign in';

    // Walang student registration o forgot-password sa admin tab.
    ['login-alt', 'login-forgot'].forEach((id) => {
      const el = document.getElementById(id);
      if (el) el.style.display = role === 'admin' ? 'none' : '';
    });

    const username = form.elements.username;
    username.placeholder = role === 'admin'
      ? username.dataset.placeholderAdmin
      : username.dataset.placeholderStudent;

    errorBox.classList.remove('show');
  }));

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    errorBox.classList.remove('show');

    const fields = {
      username: form.elements.username.value.trim(),
      password: form.elements.password.value,
      as: role
    };

    if (!fields.username || !fields.password) {
      showError(errorBox, 'Please enter your username and password.');
      return;
    }

    submitBtn.disabled = true;
    api.login(fields)
      .then((data) => { location.href = homeFor(data.user.role); })
      .catch((err) => showError(errorBox, err.message))
      .finally(() => { submitBtn.disabled = false; });
  });
}

/* --------------------------------------------------------------------------
   Student registration
   -------------------------------------------------------------------------- */

function initRegister() {
  const form = document.getElementById('register-form');
  if (!form) return;

  const errorBox = document.getElementById('register-error');
  const submitBtn = document.getElementById('register-btn');

  api.me()
    .then(({ user }) => { if (user) location.replace(homeFor(user.role)); })
    .catch(() => {});

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    errorBox.classList.remove('show');

    const fields = {
      name: form.elements.name.value.trim(),
      barangay: form.elements.barangay.value.trim(),
      email: form.elements.email.value.trim(),
      phone: form.elements.phone.value.trim(),
      username: form.elements.username.value.trim(),
      password: form.elements.password.value
    };

    if (!fields.name || !fields.barangay || !fields.email || !fields.phone ||
        !fields.username || !fields.password) {
      showError(errorBox, 'Please fill in every field.');
      return;
    }
    if (!/^[^\s@]+@[^\s@]+\.[A-Za-z]{2,}$/.test(fields.email)) {
      showError(errorBox, 'Please enter a valid email address.');
      return;
    }
    if (fields.password.length < 8) {
      showError(errorBox, 'Password must be at least 8 characters.');
      return;
    }
    if (fields.password !== form.elements.confirm.value) {
      showError(errorBox, 'The two passwords do not match.');
      return;
    }

    submitBtn.disabled = true;
    api.register(fields)
      .then(() => { location.href = 'apply.html'; })
      .catch((err) => showError(errorBox, err.message))
      .finally(() => { submitBtn.disabled = false; });
  });
}

/* --------------------------------------------------------------------------
   Forgot password (OTP demo mode -- walang totoong email)
   -------------------------------------------------------------------------- */

function initForgot() {
  const requestForm = document.getElementById('forgot-form');
  if (!requestForm) return;

  const resetForm = document.getElementById('reset-form');
  const errorBox = document.getElementById('forgot-error');
  const successBox = document.getElementById('reset-success');
  const stepOne = document.getElementById('step-one');
  const stepTwo = document.getElementById('step-two');
  const demoBox = document.getElementById('otp-demo');
  const sentTo = document.getElementById('sent-to');
  const requestBtn = document.getElementById('forgot-btn');
  const resetBtn = document.getElementById('reset-btn');

  let identifier = '';

  requestForm.addEventListener('submit', (event) => {
    event.preventDefault();
    errorBox.classList.remove('show');
    successBox.classList.remove('show');

    identifier = requestForm.elements.identifier.value.trim();
    if (!identifier) {
      showError(errorBox, 'Please enter your username or email.');
      return;
    }

    requestBtn.disabled = true;
    api.forgotPassword(identifier)
      .then((data) => {
        sentTo.textContent = data.email;
        demoBox.innerHTML = `Demo OTP: <b>${esc(data.demoCode)}</b>` +
          '<span>Walang totoong email sa demo — ito ang code na ipapakita sa presentation.</span>';
        demoBox.classList.add('show');
        stepOne.style.display = 'none';
        stepTwo.style.display = '';
        resetForm.elements.otp.focus();
      })
      .catch((err) => showError(errorBox, err.message))
      .finally(() => { requestBtn.disabled = false; });
  });

  resetForm.addEventListener('submit', (event) => {
    event.preventDefault();
    errorBox.classList.remove('show');

    const otp = resetForm.elements.otp.value.trim();
    const password = resetForm.elements.password.value;

    if (!/^\d{6}$/.test(otp)) {
      showError(errorBox, 'Enter the 6-digit code from your email.');
      return;
    }
    if (password.length < 8) {
      showError(errorBox, 'Password must be at least 8 characters.');
      return;
    }
    if (password !== resetForm.elements.confirm.value) {
      showError(errorBox, 'The two passwords do not match.');
      return;
    }

    resetBtn.disabled = true;
    api.resetPassword({ identifier, otp, password })
      .then(() => {
        stepTwo.style.display = 'none';
        demoBox.classList.remove('show');
        successBox.innerHTML = 'Password updated. <a href="login.html">Sign in with your new password</a>.';
        successBox.classList.add('show');
      })
      .catch((err) => showError(errorBox, err.message))
      .finally(() => { resetBtn.disabled = false; });
  });
}

/* --------------------------------------------------------------------------
   Student portal (apply + own status)
   -------------------------------------------------------------------------- */

async function initApply() {
  const form = document.getElementById('applicant-form');
  if (!form) return;

  const user = await requireRole('student');
  if (!user) return;

  const errorBox = document.getElementById('form-error');
  const submitBtn = document.getElementById('submit-btn');
  const statusBody = document.getElementById('status-body');
  const weightsNote = document.getElementById('weights-note');

  document.getElementById('student-name').textContent = user.name;
  form.elements.name.value = user.name;
  form.elements.barangay.value = user.barangay;

  api.config()
    .then((config) => {
      weightsNote.innerHTML = '<b>Scoring weights:</b> ' +
        config.criteria.map((c) => `${esc(c.name)} ${Math.round(c.percent)}%`).join(' · ') +
        '. Weighted scores range from 0 to 100.';
    })
    .catch(() => {
      weightsNote.textContent = 'Could not load the scoring weights from the server.';
    });

  loadMine();

  async function loadMine() {
    try {
      renderStatus(await api.application());
    } catch (err) {
      if (err.status === 401) { location.replace('login.html'); return; }
      statusBody.innerHTML = `<div class="empty">${esc(err.message)}</div>`;
    }
  }

  function renderStatus(data) {
    if (!data.submitted) {
      setFormLocked(false);
      statusBody.innerHTML = `
        <div class="empty">
          You have not submitted an application yet.<br>
          Fill in the form on the left to join the current cycle.
        </div>`;
      return;
    }

    const granted = data.status === 'GRANTED';
    statusBody.innerHTML = `
      <div class="result-banner show ${granted ? 'granted' : 'waitlist'}">
        <div class="who">${esc(data.name)} — ${granted ? 'Scholarship granted' : 'Placed on the waitlist'}</div>
        <div class="meta">
          Weighted score <b>${score(data.score)}</b> · Rank <b>${data.rank}</b> of ${data.total} ·
          ${granted ? 'within the available slots' : 'outside the available slots'}
        </div>
      </div>

      <dl class="status-list">
        <div><dt>Barangay</dt><dd>${esc(data.barangay)}</dd></div>
        <div><dt>General average</dt><dd>${data.grade}</dd></div>
        <div><dt>Monthly family income</dt><dd>${money(data.income)}</dd></div>
        <div><dt>Dependents</dt><dd>${data.dependents}</dd></div>
      </dl>

      <p class="slots-note">Your rank updates whenever another applicant is added.</p>
    `;
    setFormLocked(true);
  }

  function setFormLocked(locked) {
    ['grade', 'income', 'dependents'].forEach((key) => {
      form.elements[key].disabled = locked;
    });
    submitBtn.disabled = locked;
    submitBtn.textContent = locked ? 'Application submitted' : 'Submit application';
  }

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    errorBox.classList.remove('show');

    const fields = {
      grade: form.elements.grade.value.trim(),
      income: form.elements.income.value.trim(),
      dependents: form.elements.dependents.value.trim()
    };

    if (fields.grade === '' || fields.income === '' || fields.dependents === '') {
      showError(errorBox, 'Please fill in every field before submitting.');
      return;
    }

    submitBtn.disabled = true;
    submitBtn.textContent = 'Submitting…';

    api.submit(fields)
      .then(() => loadMine())
      .catch((err) => {
        showError(errorBox, err.message);
        setFormLocked(false);
      });
  });
}

/* --------------------------------------------------------------------------
   Admin console
   -------------------------------------------------------------------------- */

async function initAdmin() {
  const body = document.getElementById('results-body');
  if (!body) return;

  const user = await requireRole('admin');
  if (!user) return;

  document.getElementById('admin-name').textContent = user.name;

  const errorBox = document.getElementById('admin-error');
  const slotsNote = document.getElementById('slots-note');
  const resetBtn = document.getElementById('reset-btn');

  load();

  async function load() {
    try {
      render(await api.results());
    } catch (err) {
      if (err.status === 401) { location.replace('login.html'); return; }
      body.innerHTML = `<div class="empty">${esc(err.message)}<br>Is the Java server running?</div>`;
      slotsNote.textContent = '';
    }
  }

  function render(data) {
    slotsNote.textContent =
      `Weighted scores are out of 100 · Top ${data.slots} ranked applicants receive the scholarship.`;

    if (!data.results.length) {
      body.innerHTML = '<div class="empty">No applications yet in this cycle.</div>';
      return;
    }

    const rows = data.results.map((r) => `
      <tr>
        <td class="rank">${r.rank}</td>
        <td class="name">${esc(r.name)}<div class="row-sub">${esc(r.barangay)} · avg ${r.grade} · ${money(r.income)} · ${r.dependents} dep.</div></td>
        <td class="score num">${score(r.score)}</td>
        <td><span class="badge ${r.status.toLowerCase()}">${r.status}</span></td>
      </tr>
    `).join('');

    body.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Applicant</th>
              <th class="num">Score</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>${rows}</tbody>
        </table>
      </div>
      <p class="slots-note">${data.results.length} application${data.results.length === 1 ? '' : 's'} in this cycle · ${data.slots} slot${data.slots === 1 ? '' : 's'} available.</p>
    `;
  }

  resetBtn.addEventListener('click', () => {
    if (!confirm('Remove all applications from this cycle? Students will have to apply again.')) return;
    api.reset()
      .then(load)
      .catch((err) => showError(errorBox, err.message));
  });
}

/* -------------------------------------------------------------------------- */

renderSession();
initLanding();
initLogin();
initRegister();
initForgot();
initApply();
initAdmin();
