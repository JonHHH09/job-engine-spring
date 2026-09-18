'use strict';

/**
 * Operator console.
 *
 * The bearer token lives in sessionStorage for this tab only: never in a cookie
 * (no ambient authority, so a cross-site request cannot ride along), never in the
 * URL, and gone when the tab closes. Every value taken from the API is written
 * with textContent, so profile data is never interpreted as markup.
 */
(function () {
    const API = '/api/operator/v1';
    const TOKEN_KEY = 'job-engine.operator.token';
    const PAGE_SIZE = 25;

    const el = (id) => document.getElementById(id);
    const state = { cursor: null, query: '', loading: false };

    /* ---------- token ---------- */

    const getToken = () => sessionStorage.getItem(TOKEN_KEY);
    const setToken = (value) => sessionStorage.setItem(TOKEN_KEY, value);
    const clearToken = () => sessionStorage.removeItem(TOKEN_KEY);

    /* ---------- transport ---------- */

    async function api(path, options = {}) {
        const token = getToken();
        if (!token) {
            throw new ApiError('No operator token', 401);
        }
        const response = await fetch(API + path, {
            ...options,
            headers: {
                'Authorization': 'Bearer ' + token,
                'Accept': 'application/json',
                ...(options.body ? { 'Content-Type': 'application/json' } : {}),
                ...(options.headers || {})
            },
            // No cookies are used or wanted; the bearer token is the only credential.
            credentials: 'omit',
            cache: 'no-store'
        });

        if (response.status === 204) {
            return null;
        }
        if (!response.ok) {
            throw new ApiError(await problemTitle(response), response.status);
        }
        return response.json();
    }

    class ApiError extends Error {
        constructor(message, status) {
            super(message);
            this.status = status;
        }
    }

    async function problemTitle(response) {
        try {
            const problem = await response.json();
            if (problem && typeof problem.title === 'string') {
                return problem.title;
            }
        } catch (ignored) {
            // Fall through to the generic status text below.
        }
        return 'Request failed (HTTP ' + response.status + ')';
    }

    /* ---------- view helpers ---------- */

    function show(node, visible) {
        node.hidden = !visible;
    }

    function toast(message, kind) {
        const node = el('toast');
        node.textContent = message;
        node.className = 'toast' + (kind ? ' ' + kind : '');
        show(node, true);
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => show(node, false), 4200);
    }

    function setConnection(ok) {
        el('conn-dot').className = 'dot ' + (ok ? 'ok' : 'err');
    }

    function text(tag, value, className) {
        const node = document.createElement(tag);
        node.textContent = value === null || value === undefined || value === '' ? '—' : String(value);
        if (className) {
            node.className = className;
        }
        return node;
    }

    function formatDate(value) {
        if (!value) {
            return '—';
        }
        const parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleString();
    }

    function shortId(id) {
        return typeof id === 'string' && id.length > 8 ? id.slice(0, 8) : String(id ?? '—');
    }

    /* ---------- auth gate ---------- */

    function showAuth(message) {
        show(el('auth-panel'), true);
        show(el('console-panel'), false);
        show(el('lock-btn'), false);
        const error = el('auth-error');
        if (message) {
            error.textContent = message;
            show(error, true);
        } else {
            show(error, false);
        }
        setConnection(false);
        el('token-input').focus();
    }

    function showConsole() {
        show(el('auth-panel'), false);
        show(el('console-panel'), true);
        show(el('lock-btn'), true);
    }

    /* ---------- health ---------- */

    async function loadHealth() {
        const body = el('health-body');
        try {
            const health = await api('/health');
            setConnection(true);
            body.replaceChildren();

            const cleanup = health.generatedResumeCleanup || {};
            const metrics = [
                ['Database', health.status, statusClass(health.status)],
                ['Error category', health.errorCategory, health.errorCategory === 'NONE' ? 'ok' : 'warn'],
                ['Cleanup', cleanup.status, statusClass(cleanup.status)],
                ['Pending', cleanup.pendingCount, null],
                ['Processing', cleanup.processingCount, null],
                ['Expired completed', cleanup.expiredCompletedCount, null],
                ['Oldest due (s)', cleanup.oldestDueAgeSeconds, null],
                ['Repeated failure', cleanup.repeatedFailure === true ? 'YES' : 'no',
                    cleanup.repeatedFailure === true ? 'err' : 'ok']
            ];

            for (const [label, value, kind] of metrics) {
                const card = document.createElement('div');
                card.className = 'metric';
                card.append(text('div', label, 'label'), text('div', value, 'value' + (kind ? ' ' + kind : '')));
                body.append(card);
            }

            const checked = (health.metadata || {}).checkedAt;
            if (checked) {
                body.append(buildMetric('Checked at', formatDate(checked)));
            }
        } catch (error) {
            handleError(error, body, 'health');
        }
    }

    function buildMetric(label, value) {
        const card = document.createElement('div');
        card.className = 'metric';
        card.append(text('div', label, 'label'), text('div', value, 'value'));
        return card;
    }

    function statusClass(status) {
        if (status === 'UP' || status === 'HEALTHY') {
            return 'ok';
        }
        if (status === 'DEGRADED') {
            return 'warn';
        }
        return status ? 'err' : null;
    }

    /* ---------- profiles ---------- */

    async function loadProfiles(append) {
        if (state.loading) {
            return;
        }
        state.loading = true;
        const body = el('profiles-body');

        try {
            let payload;
            if (state.query) {
                payload = await api('/profiles/search?limit=' + PAGE_SIZE
                    + '&query=' + encodeURIComponent(state.query));
            } else {
                const cursor = append && state.cursor ? '&cursor=' + encodeURIComponent(state.cursor) : '';
                payload = await api('/profiles?limit=' + PAGE_SIZE + cursor);
            }
            setConnection(true);

            const profiles = (payload.profiles || []).map(unwrapProfile);
            state.cursor = payload.nextCursor || null;

            if (!append) {
                body.replaceChildren();
            }
            const existing = body.querySelector('tbody');

            if (!existing && profiles.length === 0) {
                body.replaceChildren(text('p', state.query
                    ? 'No profiles match that search.'
                    : 'No profiles yet. Ingest one through MCP or the operator API.', 'muted'));
                show(el('load-more'), false);
                return;
            }

            const tbody = existing || buildTable(body);
            for (const profile of profiles) {
                tbody.append(buildRow(profile));
            }
            // Search is a single bounded page; only the list view paginates.
            show(el('load-more'), Boolean(state.cursor) && !state.query);
        } catch (error) {
            handleError(error, body, 'profiles');
        } finally {
            state.loading = false;
        }
    }

    /**
     * The list endpoint returns flat UserProfile rows, while search wraps each hit as
     * {profile: {...}, ...}. Normalize both into one row shape.
     */
    function unwrapProfile(entry) {
        return entry && typeof entry === 'object' && entry.profile ? entry.profile : entry;
    }

    function buildTable(body) {
        const table = document.createElement('table');
        const head = document.createElement('thead');
        const headRow = document.createElement('tr');
        for (const label of ['Name', 'Email', 'Updated', 'ID', '']) {
            headRow.append(text('th', label));
        }
        head.append(headRow);
        const tbody = document.createElement('tbody');
        table.append(head, tbody);
        body.replaceChildren(table);
        return tbody;
    }

    function buildRow(profile) {
        const row = document.createElement('tr');
        row.className = 'clickable';
        row.append(
            text('td', profile.fullName),
            text('td', profile.email),
            text('td', formatDate(profile.updatedAt)),
            text('td', shortId(profile.id), 'id')
        );

        const actions = document.createElement('td');
        actions.className = 'actions';
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn btn-danger';
        remove.textContent = 'Delete';
        remove.addEventListener('click', (event) => {
            event.stopPropagation();
            deleteProfile(profile);
        });
        actions.append(remove);
        row.append(actions);

        row.addEventListener('click', () => openDetail(profile.id));
        return row;
    }

    async function deleteProfile(profile) {
        const label = profile.fullName || profile.id;
        if (!window.confirm('Delete profile "' + label + '"? This cannot be undone.')) {
            return;
        }
        try {
            await api('/profiles/' + encodeURIComponent(profile.id), { method: 'DELETE' });
            toast('Deleted ' + label, 'ok');
            show(el('detail-panel'), false);
            refreshAll();
        } catch (error) {
            handleError(error, null, 'delete');
        }
    }

    /* ---------- detail ---------- */

    async function openDetail(profileId) {
        const panel = el('detail-panel');
        const body = el('detail-body');
        show(panel, true);
        body.replaceChildren(text('p', 'Loading…', 'muted'));

        try {
            const aggregate = await api('/profiles/' + encodeURIComponent(profileId));
            const profile = aggregate.profile || {};
            const grid = document.createElement('div');
            grid.className = 'detail-grid';

            appendField(grid, 'Name', profile.fullName);
            appendField(grid, 'Email', profile.email);
            appendField(grid, 'ID', profile.id);
            appendField(grid, 'Revision', profile.revision);
            appendField(grid, 'Created', formatDate(profile.createdAt));
            appendField(grid, 'Updated', formatDate(profile.updatedAt));
            appendField(grid, 'Summary', profile.summary);

            appendChips(grid, 'Skills', aggregate.skills, (s) => s.name || s.skill);
            appendChips(grid, 'Languages', aggregate.languages, (l) => l.name || l.language);
            appendChips(grid, 'Links', aggregate.links, (l) => l.url);
            appendChips(grid, 'Projects', aggregate.projects, (p) => p.name || p.title);
            appendChips(grid, 'Experience', aggregate.experiences,
                (e) => [e.title, e.company].filter(Boolean).join(' @ '));
            appendChips(grid, 'Education', aggregate.education,
                (e) => [e.degree, e.institution].filter(Boolean).join(' — '));

            body.replaceChildren(grid);
            panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        } catch (error) {
            handleError(error, body, 'detail');
        }
    }

    function appendField(grid, label, value) {
        const field = document.createElement('div');
        field.className = 'field';
        field.append(text('div', label, 'k'), text('div', value, 'v'));
        grid.append(field);
    }

    function appendChips(grid, label, items, render) {
        if (!Array.isArray(items) || items.length === 0) {
            return;
        }
        const field = document.createElement('div');
        field.className = 'field';
        const chips = document.createElement('div');
        chips.className = 'chips';
        for (const item of items) {
            let value;
            try {
                value = render(item);
            } catch (ignored) {
                value = null;
            }
            chips.append(text('span', value || '—', 'chip'));
        }
        field.append(text('div', label, 'k'), chips);
        grid.append(field);
    }

    /* ---------- errors ---------- */

    function handleError(error, container, context) {
        const status = error instanceof ApiError ? error.status : 0;
        if (status === 401) {
            clearToken();
            showAuth('That token was rejected. Paste the current value from .env.');
            return;
        }
        setConnection(false);
        const message = status === 403
            ? 'Forbidden — the operator boundary only accepts same-origin loopback requests.'
            : (error.message || 'Request failed');
        if (container) {
            container.replaceChildren(text('p', message, 'error'));
        }
        toast(message, 'err');
        if (context === 'health') {
            setConnection(false);
        }
    }

    /* ---------- wiring ---------- */

    function refreshAll() {
        state.cursor = null;
        loadHealth();
        loadProfiles(false);
    }

    el('auth-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const value = el('token-input').value.trim();
        if (!value) {
            return;
        }
        setToken(value);
        el('token-input').value = '';
        try {
            await api('/ping');
            showConsole();
            refreshAll();
        } catch (error) {
            clearToken();
            showAuth(error.status === 401
                ? 'That token was rejected. Paste the current value from .env.'
                : (error.message || 'Could not reach the operator API.'));
        }
    });

    el('lock-btn').addEventListener('click', () => {
        clearToken();
        showAuth('Token cleared for this tab.');
    });

    el('refresh-health').addEventListener('click', loadHealth);

    el('search-form').addEventListener('submit', (event) => {
        event.preventDefault();
        state.query = el('search-input').value.trim();
        state.cursor = null;
        show(el('clear-search'), Boolean(state.query));
        loadProfiles(false);
    });

    el('clear-search').addEventListener('click', () => {
        el('search-input').value = '';
        state.query = '';
        state.cursor = null;
        show(el('clear-search'), false);
        loadProfiles(false);
    });

    el('load-more').addEventListener('click', () => loadProfiles(true));
    el('close-detail').addEventListener('click', () => show(el('detail-panel'), false));

    /* ---------- boot ---------- */

    (async function boot() {
        if (!getToken()) {
            showAuth(null);
            return;
        }
        try {
            await api('/ping');
            showConsole();
            refreshAll();
        } catch (error) {
            clearToken();
            showAuth(error.status === 401 ? 'Saved token is no longer valid.' : null);
        }
    })();
})();
