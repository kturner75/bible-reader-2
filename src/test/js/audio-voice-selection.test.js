'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const appJs = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/app.js'),
    'utf8'
);

function extractFunction(name) {
    const start = appJs.indexOf(`function ${name}(`);
    assert.ok(start >= 0, `${name} exists`);
    let i = appJs.indexOf('{', start);
    let depth = 0;
    for (; i < appJs.length; i++) {
        const ch = appJs[i];
        if (ch === '{') depth++;
        else if (ch === '}') {
            depth--;
            if (depth === 0) return appJs.slice(start, i + 1);
        }
    }
    assert.fail(`unclosed ${name}`);
}

/** A DOM stub thin enough to run the picker's render against. */
function fakeEl() {
    return { hidden: false, textContent: '', innerHTML: '', attrs: {},
             setAttribute(k, v) { this.attrs[k] = v; } };
}

function load(state) {
    const elements = {
        audioVoiceBadge: fakeEl(),
        voicePicker: fakeEl(),
        voicePickerList: fakeEl(),
        voiceSample: { paused: false, pause() { this.paused = true; } },
    };
    const ctx = {
        state,
        elements,
        escapeHtml: (t) => String(t),
        escapeAttr: (t) => String(t),
    };
    vm.createContext(ctx);
    for (const fn of ['audioVoiceKey', 'audioVoiceQuery', 'closeVoicePicker', 'renderVoicePicker']) {
        vm.runInContext(extractFunction(fn), ctx);
    }
    return ctx;
}

const VOICES = [
    { id: 'helios', name: 'Helios', gender: 'male', isDefault: true },
    { id: 'ara', name: 'Ara', gender: 'female', isDefault: false },
];

test('cache keys and query string carry the voice', () => {
    const chosen = load({ audioVoice: 'ara', audioVoices: VOICES });
    assert.equal(chosen.audioVoiceKey(), 'ara');
    assert.equal(chosen.audioVoiceQuery(), '?voice=ara');

    // No choice means the server default — and a distinct cache key from any
    // named voice, so the two never collide in audioUrlCache.
    const dflt = load({ audioVoice: null, audioVoices: VOICES });
    assert.equal(dflt.audioVoiceKey(), 'default');
    assert.equal(dflt.audioVoiceQuery(), '');
    assert.notEqual(dflt.audioVoiceKey(), chosen.audioVoiceKey());
});

test('the picker stays hidden until there is an actual choice to make', () => {
    const one = load({ audioVoice: null, audioVoices: [VOICES[0]] });
    one.renderVoicePicker();
    assert.equal(one.elements.audioVoiceBadge.hidden, true, 'badge hidden with one voice');
    assert.equal(one.elements.voicePicker.hidden, true, 'popover closed with one voice');

    const none = load({ audioVoice: null, audioVoices: [] });
    none.renderVoicePicker();
    assert.equal(none.elements.audioVoiceBadge.hidden, true, 'badge hidden with no roster');
});

test('a second voice reveals the picker with a sample button per voice', () => {
    const ctx = load({ audioVoice: null, audioVoices: VOICES });
    ctx.renderVoicePicker();

    assert.equal(ctx.elements.audioVoiceBadge.hidden, false);
    // No explicit choice yet, so the badge shows the default.
    assert.equal(ctx.elements.audioVoiceBadge.textContent, 'Helios');

    const html = ctx.elements.voicePickerList.innerHTML;
    assert.match(html, /data-voice-id="helios"/);
    assert.match(html, /data-voice-id="ara"/);
    assert.match(html, /data-voice-sample="helios"/);
    assert.match(html, /data-voice-sample="ara"/);
    assert.match(html, /female/, 'gender is surfaced when the roster supplies it');
    assert.equal((html.match(/data-voice-sample=/g) || []).length, 2,
        'every voice can be auditioned, not just the selected one');
});

test('the chosen voice is the one marked selected, not the default', () => {
    const ctx = load({ audioVoice: 'ara', audioVoices: VOICES });
    ctx.renderVoicePicker();

    assert.equal(ctx.elements.audioVoiceBadge.textContent, 'Ara');
    const html = ctx.elements.voicePickerList.innerHTML;
    assert.match(html, /data-voice-id="ara"[^>]*aria-pressed="true"/);
    assert.match(html, /data-voice-id="helios"[^>]*aria-pressed="false"/);
});

test('setAudioVoice clears the URL cache and the pre-buffer', () => {
    // Both hold the previous voice's audio; keeping either replays the old voice.
    const body = extractFunction('setAudioVoice');
    assert.match(body, /audioUrlCache\.clear\(\)/);
    assert.match(body, /ttsAudioBuffer/);
    assert.match(body, /KjvViewPrefs/, 'the choice persists as view state');
});
