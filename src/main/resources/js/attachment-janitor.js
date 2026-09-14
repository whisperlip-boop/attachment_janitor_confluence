/*
 * 화면 네 개(랭킹 · 스페이스 상세 · 중복 · 설정)와 설명서의 목차를 그린다.
 *
 * 조용히 실패하지 않는다. 요청이 401/403 이면 폴링을 멈추고 버튼을 되살리고 이유를 낸다 —
 * 그게 없으면 관리자 세션 만료가 "눌렀는데 아무 일도 없다"로 보인다.
 *
 * 라벨과 배지 문구는 서버가 넘겨준 번들에서 꺼낸다. REST 는 코드만 보내므로 같은 결과
 * 한 벌을 한국어 화면과 영어 화면이 함께 볼 수 있다.
 */
(function () {
    'use strict';

    var root = document.querySelector('.aj-app');
    if (!root) {
        return;
    }

    var base = root.getAttribute('data-base') || '';
    var screen = root.getAttribute('data-screen') || 'SPACES';
    var lang = root.getAttribute('data-lang') || 'en';
    var spaceKey = root.getAttribute('data-space') || '';

    function codeList(attribute) {
        var raw = root.getAttribute(attribute) || '';
        return raw.split(',').filter(function (code) {
            return code !== '';
        });
    }
    var api = base + '/rest/attachment-janitor/1.0/report';

    var strings = {};
    try {
        strings = JSON.parse(document.getElementById('aj-i18n').textContent);
    } catch (error) {
        strings = {};
    }

    var bannerBox = document.getElementById('aj-banner');
    var summaryBox = document.getElementById('aj-summary');
    var filterBox = document.getElementById('aj-filters');
    var tableBox = document.getElementById('aj-table');
    var asOf = document.getElementById('aj-asof');
    var scanButton = document.getElementById('aj-scan');
    var cancelButton = document.getElementById('aj-cancel');
    var csvLink = document.getElementById('aj-csv');

    var pollTimer = null;
    var lastPayload = null;
    var filters = {label: '', badge: '', extension: '', minBytes: 0, query: ''};

    /* ------------------------------------------------------------------ 공통 */

    function text(key, fallback) {
        return Object.prototype.hasOwnProperty.call(strings, key) ? strings[key]
            : (fallback === undefined ? key : fallback);
    }

    function format(template, a, b) {
        return String(template).replace('{0}', a === undefined ? '' : a)
            .replace('{1}', b === undefined ? '' : b);
    }

    function escape(value) {
        return String(value === null || value === undefined ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /* 용량 질문은 2진 단위로 묻고 답한다. */
    function bytes(value) {
        var size = Number(value) || 0;
        if (size < 1024) {
            return size + ' B';
        }
        var units = ['KB', 'MB', 'GB', 'TB', 'PB'];
        var index = -1;
        do {
            size = size / 1024;
            index++;
        } while (size >= 1024 && index < units.length - 1);
        return (size >= 100 ? size.toFixed(0) : size.toFixed(1)) + ' ' + units[index];
    }

    function number(value) {
        return (Number(value) || 0).toLocaleString();
    }

    function localTime(iso) {
        if (!iso) {
            return '';
        }
        var when = new Date(iso);
        if (isNaN(when.getTime())) {
            return iso;
        }
        function pad(part) {
            return (part < 10 ? '0' : '') + part;
        }
        return when.getFullYear() + '-' + pad(when.getMonth() + 1) + '-' + pad(when.getDate())
            + ' ' + pad(when.getHours()) + ':' + pad(when.getMinutes());
    }

    function withLang(url) {
        return url + (url.indexOf('?') < 0 ? '?' : '&') + 'lang=' + encodeURIComponent(lang);
    }

    function request(method, path, onDone, body) {
        var xhr = new XMLHttpRequest();
        xhr.open(method, api + path, true);
        xhr.setRequestHeader('Accept', 'application/json');
        xhr.setRequestHeader('X-Atlassian-Token', 'no-check');
        if (body !== undefined) {
            xhr.setRequestHeader('Content-Type', 'application/json');
        }
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) {
                return;
            }
            if (xhr.status === 401 || xhr.status === 403) {
                stopPolling();
                idleButtons();
                banner('error', text('aj.error.forbidden'));
                return;
            }
            if (xhr.status === 0 || xhr.status >= 500) {
                stopPolling();
                idleButtons();
                banner('error', text('aj.error.network'));
                return;
            }
            var payload = null;
            try {
                payload = JSON.parse(xhr.responseText);
            } catch (error) {
                payload = null;
            }
            onDone(xhr.status, payload);
        };
        xhr.send(body === undefined ? null : body);
    }

    function banner(kind, message) {
        if (!bannerBox) {
            return;
        }
        bannerBox.innerHTML = message
            ? '<div class="aj-banner aj-banner-' + kind + '">' + escape(message) + '</div>' : '';
    }

    function idleButtons() {
        if (!scanButton) {
            return;
        }
        scanButton.disabled = false;
        scanButton.textContent = text('aj.action.scan');
        if (cancelButton) {
            cancelButton.hidden = true;
        }
    }

    function busyButtons() {
        if (!scanButton) {
            return;
        }
        scanButton.disabled = true;
        scanButton.textContent = text('aj.action.scanning');
        if (cancelButton) {
            cancelButton.hidden = false;
        }
    }

    function stopPolling() {
        if (pollTimer) {
            window.clearTimeout(pollTimer);
            pollTimer = null;
        }
    }

    /* ------------------------------------------------------- 라벨 · 배지 표기 */

    function labelName(code, historyScanned) {
        /* 이력을 안 본 채로 "고아"라고 하면 검사하지 않은 것을 검사했다고 말하는 것이 된다. */
        if (code === 'orphan' && historyScanned === false) {
            return text('aj.label.name.orphan_unchecked');
        }
        return text('aj.label.name.' + code);
    }

    function labelChip(code, historyScanned) {
        return '<span class="aj-label aj-label-' + escape(code) + '" title="'
            + escape(text('aj.label.cond.' + code)) + '">'
            + escape(labelName(code, historyScanned)) + '</span>';
    }

    function badgeChips(codes) {
        if (!codes) {
            return '<span class="aj-none">-</span>';
        }
        return codes.split(' ').map(function (code) {
            return '<span class="aj-badge aj-badge-' + escape(code) + '" title="'
                + escape(text('aj.badge.desc.' + code)) + '">'
                + escape(text('aj.badge.name.' + code)) + '</span>';
        }).join(' ');
    }

    function contentUrl(contentId) {
        return base + '/pages/viewpage.action?pageId=' + encodeURIComponent(contentId);
    }

    /* --------------------------------------------------------------- 배너 모음 */

    function renderBanners(payload) {
        var run = payload.run;
        var shown = payload.shown;
        var progress = payload.progress;
        var parts = '';

        if (progress && progress.running) {
            var phase = text(progress.phaseKey || 'aj.phase.none');
            var label = format(text('aj.state.scanning'), phase, number(progress.processed));
            var width = progress.percent < 0 ? 100 : progress.percent;
            parts += '<div class="aj-banner aj-banner-info">' + escape(label)
                + '<div class="aj-progress"><span style="width:' + width
                + '%"></span></div></div>';
        }
        if (run && run.status === 'FAILED') {
            parts += '<div class="aj-banner aj-banner-error">'
                + escape(format(text('aj.state.failed'), run.errorMessage || '')) + '</div>';
        } else if (run && run.status === 'CANCELLED') {
            parts += '<div class="aj-banner aj-banner-warn">'
                + escape(text('aj.state.cancelled')) + '</div>';
        }
        if (payload.stale) {
            parts += warn(text('aj.state.stale'));
        }

        /* 아래 넷은 "이 수치가 완전하지 않다"는 고백이다. 조용히 두면 표가 완전한
           것으로 읽힌다. */
        if (shown && shown.skippedCount > 0) {
            parts += warn(format(text('aj.state.skipped'), number(shown.skippedCount)));
        }
        if (shown && shown.parseFailures > 0) {
            parts += warn(format(text('aj.state.parsefail'), number(shown.parseFailures)));
        }
        if (shown && shown.unresolvedRefs > 0) {
            parts += warn(format(text('aj.state.unresolved'), number(shown.unresolvedRefs)));
        }
        if (shown && shown.hashBudgetHit) {
            parts += warn(text('aj.state.hashbudget'));
        }
        if (shown && shown.historyScanned === false) {
            parts += '<div class="aj-banner aj-banner-info">'
                + escape(text('aj.state.nohistory')) + '</div>';
        }
        bannerBox.innerHTML = parts;
    }

    function warn(message) {
        return '<div class="aj-banner aj-banner-warn">' + escape(message) + '</div>';
    }

    function renderAsOf(shown) {
        if (!asOf) {
            return;
        }
        asOf.textContent = shown && shown.finishedAt
            ? format(text('aj.asof'), localTime(shown.finishedAt)) : '';
    }

    function tiles(items) {
        summaryBox.innerHTML = items.map(function (tile) {
            return '<div class="aj-tile' + (tile[2] || '') + '"><div class="aj-tile-label">'
                + escape(tile[0]) + '</div><div class="aj-tile-value">'
                + escape(tile[1]) + '</div></div>';
        }).join('');
    }

    function emptyMessage(payload) {
        return '<p class="aj-muted">'
            + escape(text(payload.run ? 'aj.state.noresults' : 'aj.state.never')) + '</p>';
    }

    function th(key, klass) {
        return '<th class="' + klass.trim() + '">' + escape(text(key)) + '</th>';
    }

    function num(value, klass) {
        if (value === null || value === undefined) {
            return '<td class="aj-num"><span class="aj-none">-</span></td>';
        }
        return '<td class="aj-num' + (klass ? ' ' + klass : '') + '">' + escape(value) + '</td>';
    }

    /* ------------------------------------------------------------ 랭킹 화면 */

    function renderSpaces(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        tiles([
            [text('aj.summary.files'), number(shown.fileCount), ''],
            [text('aj.summary.total'), bytes(shown.totalBytes), ''],
            [text('aj.summary.oldversions'),
                bytes(shown.oldVersionBytes) + ' · ' + number(shown.oldVersionCount),
                ' aj-tile-accent'],
            [text('aj.summary.orphans'),
                bytes(shown.orphanBytes) + ' · ' + number(shown.orphanCount),
                ' aj-tile-accent'],
            [text('aj.summary.duplicates'),
                bytes(shown.duplicateReclaimable) + ' · ' + number(shown.duplicateGroups),
                '']
        ]);

        var spaces = payload.spaces || [];
        if (!spaces.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.empty'))
                + '</p>';
            return;
        }
        var largest = spaces.reduce(function (top, row) {
            return Math.max(top, Number(row.totalBytes) || 0);
        }, 0) || 1;

        var head = '<table class="aj-table"><thead><tr>'
            + th('aj.col.space', '') + th('aj.col.files', ' aj-num')
            + th('aj.col.total', ' aj-num') + th('aj.col.latest', ' aj-num')
            + th('aj.col.oldversions', ' aj-num') + th('aj.col.orphans', ' aj-num')
            + th('aj.col.risky', ' aj-num') + th('aj.col.duplicates', ' aj-num')
            + '</tr></thead><tbody>';

        var body = spaces.map(function (row) {
            var total = Number(row.totalBytes) || 0;
            var old = Number(row.oldVersionBytes) || 0;
            var name = row.spaceKey
                ? (row.spaceName ? row.spaceName + ' (' + row.spaceKey + ')' : row.spaceKey)
                : text('aj.col.nospace');
            var latestWidth = Math.round((total - old) / largest * 100);
            var oldWidth = Math.round(old / largest * 100);
            var link = row.spaceKey
                ? '<a href="' + escape(withLang(base + '/plugins/servlet/attachment-janitor'
                    + '/space?key=' + encodeURIComponent(row.spaceKey))) + '">'
                    + escape(name) + '</a>'
                : escape(name);

            return '<tr><td>' + link
                + '<span class="aj-bar-cell">'
                + '<span class="aj-bar-fill" style="width:' + latestWidth + '%"></span>'
                + '<span class="aj-bar-fill aj-bar-fill-old" style="width:' + oldWidth
                + '%;margin-top:-4px;margin-left:' + latestWidth + '%"></span>'
                + '</span></td>'
                + num(number(row.fileCount))
                + num(bytes(total))
                + num(bytes(row.latestBytes))
                + num(old > 0 ? bytes(old) + ' · ' + number(row.oldVersionCount) : null,
                    'aj-warn')
                + num(row.orphanCount > 0
                    ? bytes(row.orphanBytes) + ' · ' + number(row.orphanCount) : null,
                    'aj-warn')
                + num(row.riskyCount > 0 ? number(row.riskyCount) : null)
                + num(row.duplicateCount > 0 ? number(row.duplicateCount) : null)
                + '</tr>';
        }).join('');

        tableBox.innerHTML = head + body + '</tbody></table>'
            + '<p class="aj-foot">' + escape(text('aj.note.nospace')) + '</p>';
        if (csvLink) {
            csvLink.setAttribute('href', api + '/spaces.csv');
        }
    }

    /* ------------------------------------------------------- 스페이스 상세 화면 */

    function renderDetail(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            filterBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        if (!shown.hasDetail) {
            filterBox.innerHTML = '';
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.nodetail'))
                + '</p>';
            return;
        }

        var rows = payload.attachments || [];
        var visible = rows.filter(matchesFilters);
        var totals = visible.reduce(function (acc, row) {
            acc.files++;
            acc.bytes += (Number(row.latestBytes) || 0) + (Number(row.oldVersionBytes) || 0);
            return acc;
        }, {files: 0, bytes: 0});

        tiles([
            [text('aj.summary.spacefiles'),
                number(totals.files) + ' / ' + number(rows.length), ''],
            [text('aj.summary.total'), bytes(totals.bytes), '']
        ]);
        renderFilters(rows);

        if (!visible.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.state.nomatch'))
                + '</p>';
            return;
        }

        /* 기본 정렬은 라벨의 정리 순위다. 서버가 보내 준 값을 쓴다 — 화면이 순서를 다시
           적으면 열거형과 어긋난다. */
        visible.sort(function (a, b) {
            if (a.cleanupRank !== b.cleanupRank) {
                return a.cleanupRank - b.cleanupRank;
            }
            return (b.latestBytes + b.oldVersionBytes) - (a.latestBytes + a.oldVersionBytes);
        });

        var head = '<table class="aj-table"><thead><tr>'
            + th('aj.col.filename', '') + th('aj.col.container', '')
            + th('aj.col.label', '') + th('aj.col.badges', '')
            + th('aj.col.latest', ' aj-num') + th('aj.col.versions', ' aj-num')
            + th('aj.col.oldversions', ' aj-num') + th('aj.col.modified', ' aj-num')
            + th('aj.col.refs', ' aj-num')
            + '</tr></thead><tbody>';

        var body = visible.map(function (row, index) {
            var container = row.containerId
                ? '<a href="' + escape(contentUrl(row.containerId)) + '">'
                    + escape(row.containerTitle || row.containerId) + '</a>'
                : escape(row.containerTitle);
            var refCell = row.refCount > 0
                ? '<a href="#" class="aj-expand" data-row="' + index + '">'
                    + escape(number(row.refCount)) + '</a>'
                : '<span class="aj-none">-</span>';
            return '<tr><td>' + escape(row.fileName) + '</td>'
                + '<td>' + container + '<div class="aj-meta">'
                + escape(row.containerType) + '</div></td>'
                + '<td>' + labelChip(row.label, shown.historyScanned) + '</td>'
                + '<td>' + badgeChips(row.badgeCodes) + '</td>'
                + num(bytes(row.latestBytes))
                + num(number(row.versionCount))
                + num(row.oldVersionCount > 0
                    ? bytes(row.oldVersionBytes) + ' · ' + number(row.oldVersionCount)
                    : null, 'aj-warn')
                + num(row.lastModified ? localTime(row.lastModified) : null)
                + '<td class="aj-num">' + refCell + '</td>'
                + '</tr>'
                + '<tr class="aj-refs" id="aj-refs-' + index + '" hidden><td colspan="9">'
                + renderRefs(row) + '</td></tr>';
        }).join('');

        tableBox.innerHTML = head + body + '</tbody></table>';
        if (csvLink) {
            csvLink.setAttribute('href',
                api + '/space.csv?key=' + encodeURIComponent(spaceKey));
        }

        Array.prototype.forEach.call(tableBox.querySelectorAll('.aj-expand'),
            function (link) {
                link.addEventListener('click', function (event) {
                    event.preventDefault();
                    var target = document.getElementById(
                        'aj-refs-' + link.getAttribute('data-row'));
                    target.hidden = !target.hidden;
                });
            });
    }

    /* 라벨의 근거. 펼쳐 볼 수 없는 라벨은 신뢰받지 못한다. */
    function renderRefs(row) {
        var refs = row.refs || [];
        if (!refs.length) {
            return '<span class="aj-muted">' + escape(text('aj.refs.none')) + '</span>';
        }
        var items = refs.map(function (ref) {
            return '<li>' + escape(text('aj.ref.kind.' + ref.kind)) + ' — '
                + '<a href="' + escape(contentUrl(ref.contentId)) + '">'
                + escape(ref.title || ref.contentId) + '</a>'
                + (ref.spaceKey
                    ? ' <span class="aj-meta">(' + escape(ref.spaceKey) + ')</span>' : '')
                + '</li>';
        }).join('');
        var more = row.refCount > refs.length
            ? '<p class="aj-meta">'
                + escape(format(text('aj.refs.more'), number(row.refCount - refs.length)))
                + '</p>'
            : '';
        return '<ul class="aj-ref-list">' + items + '</ul>' + more;
    }

    function matchesFilters(row) {
        if (filters.label && row.label !== filters.label) {
            return false;
        }
        if (filters.badge && (' ' + (row.badgeCodes || '') + ' ')
                .indexOf(' ' + filters.badge + ' ') < 0) {
            return false;
        }
        if (filters.extension && row.extension !== filters.extension) {
            return false;
        }
        if (filters.minBytes && (Number(row.latestBytes) || 0) < filters.minBytes) {
            return false;
        }
        if (filters.query
                && row.fileName.toLowerCase().indexOf(filters.query.toLowerCase()) < 0) {
            return false;
        }
        return true;
    }

    // 서버가 열거형을 순회해 넘겨준 목록이다. 여기에 코드를 다시 적지 않는다 —
    // 열거형에 값을 더했을 때 필터에서만 조용히 빠지는 사고를 막는다.
    var FILTER_LABELS = codeList('data-labels');
    var FILTER_BADGES = codeList('data-badges');

    function renderFilters(rows) {
        if (filterBox.getAttribute('data-built') === 'yes') {
            return;
        }
        var extensions = {};
        rows.forEach(function (row) {
            if (row.extension) {
                extensions[row.extension] = true;
            }
        });

        function select(id, labelKey, options) {
            return '<label>' + escape(text(labelKey)) + ' <select id="' + id + '">'
                + '<option value="">' + escape(text('aj.filter.all')) + '</option>'
                + options.map(function (option) {
                    return '<option value="' + escape(option[0]) + '">'
                        + escape(option[1]) + '</option>';
                }).join('') + '</select></label>';
        }

        filterBox.innerHTML =
            select('aj-f-label', 'aj.filter.label', FILTER_LABELS.map(function (code) {
                return [code, text('aj.label.name.' + code)];
            }))
            + select('aj-f-badge', 'aj.filter.badge', FILTER_BADGES.map(function (code) {
                return [code, text('aj.badge.name.' + code)];
            }))
            + select('aj-f-ext', 'aj.filter.extension',
                Object.keys(extensions).sort().map(function (ext) {
                    return [ext, ext];
                }))
            + select('aj-f-size', 'aj.filter.minsize',
                [['1048576', '1 MB'], ['10485760', '10 MB'], ['104857600', '100 MB']])
            + '<label>' + escape(text('aj.filter.name'))
            + ' <input type="search" id="aj-f-query" value=""></label>';
        filterBox.setAttribute('data-built', 'yes');

        bind('aj-f-label', 'change', function (value) { filters.label = value; });
        bind('aj-f-badge', 'change', function (value) { filters.badge = value; });
        bind('aj-f-ext', 'change', function (value) { filters.extension = value; });
        bind('aj-f-size', 'change', function (value) { filters.minBytes = Number(value) || 0; });
        bind('aj-f-query', 'input', function (value) { filters.query = value; });
    }

    function bind(id, event, apply) {
        var element = document.getElementById(id);
        if (!element) {
            return;
        }
        element.addEventListener(event, function () {
            apply(element.value);
            if (lastPayload) {
                renderDetail(lastPayload);
            }
        });
    }

    /* ------------------------------------------------------------- 중복 화면 */

    function renderDuplicates(payload) {
        var shown = payload.shown;
        if (!shown) {
            summaryBox.innerHTML = '';
            tableBox.innerHTML = emptyMessage(payload);
            return;
        }
        var groups = payload.groups || [];
        if (!groups.length) {
            tableBox.innerHTML = '<p class="aj-muted">' + escape(text('aj.dup.none')) + '</p>';
            return;
        }

        var head = '<table class="aj-table"><thead><tr>'
            + th('aj.col.dupfiles', ' aj-num') + th('aj.col.unit', ' aj-num')
            + th('aj.col.reclaim', ' aj-num') + th('aj.col.certainty', '')
            + th('aj.col.members', '')
            + '</tr></thead><tbody>';

        var body = groups.map(function (group) {
            var members = (group.members || []).map(function (row) {
                return '<li>' + escape(row.spaceKey || text('aj.col.nospace')) + ' / '
                    + '<a href="' + escape(contentUrl(row.containerId)) + '">'
                    + escape(row.containerTitle || row.containerId) + '</a> / '
                    + escape(row.fileName) + '</li>';
            }).join('');
            return '<tr>'
                + num(number(group.fileCount))
                + num(bytes(group.unitBytes))
                + '<td class="aj-num aj-warn">' + escape(bytes(group.reclaimableBytes))
                + '</td>'
                + '<td>' + (group.exact
                    ? '<span class="aj-badge aj-badge-duplicate">'
                        + escape(text('aj.dup.exact')) + '</span>'
                    : '<span class="aj-badge aj-badge-maybe">'
                        + escape(text('aj.dup.maybe')) + '</span>') + '</td>'
                + '<td><ul class="aj-ref-list">' + members + '</ul></td>'
                + '</tr>';
        }).join('');

        tableBox.innerHTML = head + body + '</tbody></table>'
            + '<p class="aj-foot">' + escape(text('aj.dup.note')) + '</p>';
        if (csvLink) {
            csvLink.setAttribute('href', api + '/duplicates.csv');
        }
    }

    /* ------------------------------------------------------------- 설정 화면 */

    var SETTING_FIELDS = [
        ['oldVersionCount', 'aj.set.oldversioncount', false],
        ['oldVersionBytes', 'aj.set.oldversionbytes', true],
        ['largeBytes', 'aj.set.largebytes', true],
        ['staleDays', 'aj.set.staledays', false],
        ['duplicateByteBudget', 'aj.set.hashbudget', true],
        ['keepRuns', 'aj.set.keepruns', false]
    ];

    function renderSettings(settings) {
        var html = '<table class="aj-table aj-settings"><tbody>';
        SETTING_FIELDS.forEach(function (field) {
            var value = field[2] ? Math.round(settings[field[0]] / 1048576) : settings[field[0]];
            html += '<tr><th>' + escape(text(field[1])) + '</th>'
                + '<td><input type="number" min="0" id="aj-s-' + field[0] + '" value="'
                + escape(value) + '"></td>'
                + '<td class="aj-meta">' + escape(text(field[1] + '.hint')) + '</td></tr>';
        });
        html += '<tr><th>' + escape(text('aj.set.scanhistory')) + '</th>'
            + '<td><input type="checkbox" id="aj-s-scanHistory"'
            + (settings.scanHistory ? ' checked' : '') + '></td>'
            + '<td class="aj-meta">' + escape(text('aj.set.scanhistory.hint')) + '</td></tr>'
            + '<tr><th>' + escape(text('aj.set.dupmode')) + '</th>'
            + '<td><select id="aj-s-duplicateMode">'
            + '<option value="QUICK"' + (settings.duplicateMode === 'QUICK' ? ' selected' : '')
            + '>' + escape(text('aj.set.dupmode.quick')) + '</option>'
            + '<option value="FULL"' + (settings.duplicateMode === 'FULL' ? ' selected' : '')
            + '>' + escape(text('aj.set.dupmode.full')) + '</option>'
            + '</select></td>'
            + '<td class="aj-meta">' + escape(text('aj.set.dupmode.hint')) + '</td></tr>'
            + '</tbody></table>'
            + '<div class="aj-bar"><button type="button" class="aui-button aui-button-primary"'
            + ' id="aj-save">' + escape(text('aj.action.save')) + '</button>'
            + '<span class="aj-asof" id="aj-saved"></span></div>'
            + '<p class="aj-foot">' + escape(text('aj.set.note')) + '</p>';

        tableBox.innerHTML = html;
        document.getElementById('aj-save').addEventListener('click', saveSettings);
    }

    function saveSettings() {
        var body = {
            scanHistory: document.getElementById('aj-s-scanHistory').checked,
            duplicateMode: document.getElementById('aj-s-duplicateMode').value
        };
        SETTING_FIELDS.forEach(function (field) {
            var element = document.getElementById('aj-s-' + field[0]);
            var value = element ? (parseInt(element.value, 10) || 0) : 0;
            body[field[0]] = field[2] ? value * 1048576 : value;
        });
        request('PUT', '/settings', function (status, payload) {
            if (status !== 200 || !payload) {
                banner('error', text('aj.error.network'));
                return;
            }
            /* 저장한 값이 아니라 서버가 돌려준 값을 다시 그린다 — 상·하한에 걸려 조정된
               값을 관리자가 보아야 한다. */
            renderSettings(payload);
            document.getElementById('aj-saved').textContent = text('aj.set.saved');
        }, JSON.stringify(body));
    }

    /* -------------------------------------------------------- 설명서 목차 */

    function buildToc() {
        var list = document.getElementById('aj-toc');
        var body = document.querySelector('.aj-help-body');
        if (!list || !body) {
            return;
        }
        var html = '';
        Array.prototype.forEach.call(body.querySelectorAll('h2'), function (heading, index) {
            if (!heading.id) {
                heading.id = 'aj-h-' + index;
            }
            html += '<li><a href="#' + escape(heading.id) + '">'
                + escape(heading.textContent) + '</a></li>';
        });
        list.innerHTML = html;
    }

    /* ------------------------------------------------------------------ 적재 */

    function load() {
        if (screen === 'SETTINGS') {
            request('GET', '/settings', function (status, payload) {
                if (status !== 200 || !payload) {
                    banner('error', text('aj.error.network'));
                    return;
                }
                renderSettings(payload);
            });
            return;
        }

        var path = screen === 'DUPLICATES' ? '/duplicates'
            : (screen === 'SPACE_DETAIL' ? '/space?key=' + encodeURIComponent(spaceKey)
                : '/spaces');

        request('GET', path, function (status, payload) {
            if (status !== 200 || !payload) {
                banner('error', text('aj.error.network'));
                return;
            }
            lastPayload = payload;
            renderBanners(payload);
            renderAsOf(payload.shown);

            if (screen === 'DUPLICATES') {
                renderDuplicates(payload);
            } else if (screen === 'SPACE_DETAIL') {
                renderDetail(payload);
            } else {
                renderSpaces(payload);
            }

            if (payload.progress && payload.progress.running) {
                busyButtons();
                pollTimer = window.setTimeout(load, 1500);
            } else {
                stopPolling();
                idleButtons();
            }
        });
    }

    if (scanButton) {
        scanButton.addEventListener('click', function () {
            busyButtons();
            banner('', '');
            request('POST', '/scan', function (status) {
                if (status === 409) {
                    banner('warn', text('aj.error.busy'));
                }
                load();
            });
        });
    }
    if (cancelButton) {
        cancelButton.addEventListener('click', function () {
            cancelButton.disabled = true;
            request('DELETE', '/scan', function () {
                cancelButton.disabled = false;
                load();
            });
        });
    }

    if (screen === 'HELP') {
        buildToc();
    } else {
        load();
    }
}());
