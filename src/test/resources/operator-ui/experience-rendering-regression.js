const fs = require('fs');

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName;
        this.children = [];
        this.listeners = new Map();
        this.className = '';
        this.hidden = false;
        this.textContent = '';
        this.value = '';
    }

    append(...nodes) {
        this.children.push(...nodes);
    }

    replaceChildren(...nodes) {
        this.children = nodes;
    }

    addEventListener(type, listener) {
        this.listeners.set(type, [...(this.listeners.get(type) || []), listener]);
    }

    querySelector(tagName) {
        return descendants(this).find((node) => node.tagName === tagName) || null;
    }

    scrollIntoView() {
    }
}

function descendants(node) {
    return node.children.flatMap((child) => [child, ...descendants(child)]);
}

const ids = [
    'auth-panel', 'console-panel', 'lock-btn', 'auth-error', 'token-input', 'toast', 'conn-dot',
    'health-body', 'profiles-body', 'load-more', 'detail-panel', 'detail-body', 'auth-form',
    'refresh-health', 'search-form', 'search-input', 'clear-search', 'close-detail'
];
const elements = new Map(ids.map((id) => [id, new FakeElement('div')]));
const storage = new Map([['job-engine.operator.token', 'test-token']]);

global.document = {
    createElement: (tagName) => new FakeElement(tagName),
    getElementById: (id) => elements.get(id)
};
global.window = { confirm: () => false };
global.sessionStorage = {
    getItem: (key) => storage.get(key) || null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: (key) => storage.delete(key)
};
global.fetch = async (url) => {
    const response = (body) => ({ status: 200, ok: true, json: async () => body });
    if (url.endsWith('/ping')) {
        return response({ status: 'ok' });
    }
    if (url.endsWith('/health')) {
        return response({ status: 'UP', generatedResumeCleanup: {} });
    }
    if (url.includes('/profiles?')) {
        return response({ profiles: [{ id: 'profile-1', fullName: 'Ada Lovelace', email: 'ada@example.test' }] });
    }
    if (url.endsWith('/profiles/profile-1')) {
        return response({
            profile: { id: 'profile-1', fullName: 'Ada Lovelace' },
            experiences: [{ title: 'Principal Engineer', company: 'Analytical Engines' }]
        });
    }
    throw new Error(`Unexpected request: ${url}`);
};

const app = fs.readFileSync(process.argv[2], 'utf8');
eval(app);

async function settle() {
    for (let turn = 0; turn < 8; turn++) {
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
}

(async () => {
    await settle();
    const row = descendants(elements.get('profiles-body'))
        .find((node) => node.tagName === 'tr' && node.listeners.has('click'));
    if (!row) {
        throw new Error('The console did not render a clickable profile row.');
    }
    row.listeners.get('click')[0]();
    await settle();

    const chips = descendants(elements.get('detail-body'))
        .filter((node) => node.className === 'chip')
        .map((node) => node.textContent);
    const expected = ['Principal Engineer @ Analytical Engines'];
    if (JSON.stringify(chips) !== JSON.stringify(expected)) {
        throw new Error(`Expected experience chip ${JSON.stringify(expected)} but received ${JSON.stringify(chips)}.`);
    }
    console.log('PASS');
})().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
});
