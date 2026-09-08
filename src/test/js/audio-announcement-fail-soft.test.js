'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const appJs = fs.readFileSync(
    path.join(__dirname, '../../main/resources/static/app.js'),
    'utf8'
);

function extractFunction(name) {
    const start = appJs.indexOf(`function ${name}(`);
    assert.ok(start >= 0, `${name} exists`);
    // Naive brace match from the function keyword — enough for these small helpers.
    let i = appJs.indexOf('{', start);
    let depth = 0;
    for (; i < appJs.length; i++) {
        const ch = appJs[i];
        if (ch === '{') depth++;
        else if (ch === '}') {
            depth--;
            if (depth === 0) {
                return appJs.slice(start, i + 1);
            }
        }
    }
    assert.fail(`unclosed ${name}`);
}

test('handleAudioError fail-softs announcement media errors instead of stopAudio', () => {
    const body = extractFunction('handleAudioError');
    assert.match(body, /state\.audioAnnouncing/);
    assert.match(body, /skipAnnouncement\s*\(/);
    // Verse / normal playback still tears down.
    assert.match(body, /stopAudio\s*\(/);
    // The announcing branch must reach skip before any unconditional stop.
    const announceIdx = body.search(/if\s*\(\s*state\.audioAnnouncing\s*\)/);
    const skipIdx = body.indexOf('skipAnnouncement');
    const stopIdx = body.lastIndexOf('stopAudio');
    assert.ok(announceIdx >= 0, 'guards on audioAnnouncing');
    assert.ok(skipIdx > announceIdx, 'skipAnnouncement is inside the announcing path');
    assert.ok(stopIdx > skipIdx, 'stopAudio remains the non-announcement fallback');
});

test('skipAnnouncement bumps audioAnnounceEpoch so a late play catch cannot clobber the next clip', () => {
    const skip = extractFunction('skipAnnouncement');
    assert.match(skip, /audioAnnounceEpoch\s*\+\+/);
    assert.match(appJs, /audioAnnounceEpoch:\s*0/);
    assert.match(appJs, /epoch !== state\.audioAnnounceEpoch/);
});
